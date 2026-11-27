@echo off
setlocal EnableDelayedExpansion

:: ============================================================
::  MG4 APK İmzalama ve Kurma Scripti
::
::  Referans: adammcdonagh/MG4-Custom-Launcher/sign_apk.sh
::  İmzalama: apksigner --key platform.pk8 --cert platform.x509.pem
::  (p12 keystore değil — araç bu yöntemi kabul ediyor)
::
::  Kullanım: Android Studio'da assembleDebug yaptıktan sonra
::             bu dosyayı çift tıkla.
:: ============================================================

set SCRIPT_DIR=%~dp0
set PROJECT_DIR=%SCRIPT_DIR%..
REM DriveHub_Kadran icin standart release APK yolu
set APK_IN=%PROJECT_DIR%\app\build\outputs\apk\release\app-release-unsigned.apk
set APK_OUT=%PROJECT_DIR%\app\build\outputs\apk\release\app-release-signed.apk
:: PLATFORM_KEYS_DIR may point at the AOSP test key; default is a local copy in tools\
if not defined PLATFORM_KEYS_DIR set PLATFORM_KEYS_DIR=%SCRIPT_DIR%
if not "%PLATFORM_KEYS_DIR:~-1%"=="\" set PLATFORM_KEYS_DIR=%PLATFORM_KEYS_DIR%\
set PLATFORM_PK8=%PLATFORM_KEYS_DIR%platform.pk8
set PLATFORM_PEM=%PLATFORM_KEYS_DIR%platform.x509.pem

REM build.gradle icinden versionName oku (DriveHub_{versionName}.apk icin)
set VERSION_NAME=unknown
for /f "tokens=2 delims== " %%v in ('findstr /R /C:"versionName" "%PROJECT_DIR%\app\build.gradle"') do (
    set VERSION_NAME=%%v
)
REM Cift tirnaklari temizle: "0.7.2" -> 0.7.2
set VERSION_NAME=%VERSION_NAME:"=%
set APK_TOOLS_NAME=DriveHub_Kamera_%VERSION_NAME%.apk
set APK_TOOLS_PATH=%SCRIPT_DIR%%APK_TOOLS_NAME%

:: apksigner.jar yolunu otomatik bul (en yüksek build-tools sürümü)
set APKSIGNER_JAR=
set BUILD_TOOLS_BASE=%LOCALAPPDATA%\Android\Sdk\build-tools
for /d %%v in ("%BUILD_TOOLS_BASE%\*") do (
    set APKSIGNER_JAR=%%v\lib\apksigner.jar
)

:: -------- Kontroller --------

if not exist "%APK_IN%" (
    echo.
    echo [HATA] APK bulunamadi: %APK_IN%
    echo        Android Studio'da Build ^> Make Project yapip tekrar dene.
    pause & exit /b 1
)

if not exist "%PLATFORM_PK8%" (
    echo.
    echo [HATA] platform.pk8 bulunamadi: %PLATFORM_PK8%
    pause & exit /b 1
)

if not exist "%PLATFORM_PEM%" (
    echo.
    echo [HATA] platform.x509.pem bulunamadi: %PLATFORM_PEM%
    pause & exit /b 1
)

if not exist "%APKSIGNER_JAR%" (
    echo.
    echo [HATA] apksigner.jar bulunamadi. Android SDK build-tools yuklu olmali.
    pause & exit /b 1
)

echo.
echo ============================================================
echo  DriveHub_Kadran RELEASE APK Imzalama ve Kurma
echo ============================================================
echo  Kaynak APK : %APK_IN%
echo  Cikti  APK : %APK_OUT%
echo  Tools APK  : %APK_TOOLS_PATH%
echo  Yontem     : --key platform.pk8 --cert platform.x509.pem
echo.

:: -------- İmzala (referans projeyle aynı yöntem) --------
echo [1/2] Imzalaniyor...
java -jar "%APKSIGNER_JAR%" sign ^
    --key "%PLATFORM_PK8%" ^
    --cert "%PLATFORM_PEM%" ^
    --out "%APK_OUT%" ^
    "%APK_IN%"

if errorlevel 1 (
    echo.
    echo [HATA] Imzalama basarisiz!
    pause & exit /b 1
)
echo [1/2] Imzalama tamamlandi.

:: -------- tools/ klasorune kopyala --------
copy /Y "%APK_OUT%" "%APK_TOOLS_PATH%" >nul
echo       Kopya: %APK_TOOLS_PATH%

:: -------- ADB kontrol --------
adb devices 2>nul | findstr /v "List" | findstr "device" >nul
if errorlevel 1 (
    echo.
    echo [UYARI] ADB ile bagli cihaz bulunamadi.
    echo         Araci USB ile bagla ve tekrar dene.
    echo         Imzali APK hazir: %APK_OUT%
    pause & exit /b 0
)

:: -------- Yükle --------
echo [2/2] Araca yukleniyor...
echo        Once eski surum kaldiriliyor...
::adb uninstall com.example.mg4_v3 >nul 2>&1
::adb install "%APK_OUT%"
adb install -r "%APK_OUT%"

if errorlevel 1 (
    echo.
    echo [HATA] Yukleme basarisiz! Yukaridaki hataya bak.
) else (
    echo.
    echo ============================================================
    echo  TAMAMLANDI! Uygulama araca yuklendi.
    echo  Logcat icin Android Studio Logcat sekmesini ac.
    echo ============================================================
)

echo.
pause
