# Parking-sensor bars in the camera image and 3D "bowl" view

Contributed by **andneg7** ([Rivela 360](https://github.com/andneg7/rivela360-mg4)) to MG4-360-Camera-App, as agreed in
[andneg7/rivela360-mg4#1](https://github.com/andneg7/rivela360-mg4/issues/1). The code is in
`app/src/main/java/com/drivehub/kamera/rivela/` and has no Android dependency except where noted, so it can be
tested on a PC.

It is not wired into the app yet: these are the building blocks plus the ideas behind them, to integrate as you prefer.

## Files

| File | What it does |
|---|---|
| `FisheyeCamera.java` | OpenCV fisheye camera model (fx, fy, cx, cy, k1..k4, R, t) in a car-centred world. Projects world points to image pixels and gives a 0..1 "validity" used to fade cameras near the edge of the lens circle. |
| `CarFrame.java` | Where the car body is in the world (centre, length, width). Can be derived from the camera positions. |
| `PdcBars.java` | Geometry of the four rear sensor bars on the ground, colours, and the smooth glide between levels. |
| `GroundProjector.java` | Turns any ground line with a real width (sensor bars, guide lines) into filled polygons in the fisheye image. |
| `Bowl.java` | Builds the 3D bowl mesh: positions, texture coordinates for the 4 cameras and blending weights. |
| `BowlShaders.java` | GLSL ES 2.0 shaders for the bowl, for RGBA or planar YUV camera textures. |

## Conventions

- World: **millimetres**, x to the right of the car, y towards the **rear**, z **down**, ground at z = 0.
- Camera frame: OpenCV (x right, y down, z forward).
- Camera order in the bowl vertex: LEFT, RIGHT, FRONT, REAR.

## What you need to provide: the camera calibration

Everything here needs, for each camera, the fisheye intrinsics and its pose relative to the car. The car has a factory
calibration, but how Rivela 360 reads it comes from our analysis of the stock app, so it is **not part of this
contribution**. You can get the same data your own way, for example:

1. intrinsics with `cv::fisheye::calibrate` and a checkerboard (once per camera model);
2. pose with `cv::solvePnP` (fisheye-undistorted points) on a few markers on the ground at known positions around
   the car, e.g. tape crosses measured from the bumpers and the sides.

Then `FisheyeCamera.fromRvec(...)` and `CarFrame.fromCameras(...)` (MG4 body: 4287 x 1836 mm).

## 1. Sensor bars inside the camera image

The idea: draw the bars **on the ground, in millimetres**, and project them into the image. Then they sit exactly
where the obstacle is, they get thinner with distance like painted lines, and the same geometry works in the top view
and in 3D.

- **Shape:** 4 sectors, left to right, on 76 degrees (19 each, 2 degree gap). Every bar is an arc around one centre
  on the car axis, 1.5 m in front of the rear bumper, so the bars follow the bumper. Bar thickness 120 mm, outer edge
  at the sector distance.
- **Distance:** the car gives one level per sector (1 closest ... 9 farthest) and the exact distance in cm of the
  closest obstacle. Use that exact value for the sector(s) at the closest level; for the others use a level-to-cm
  table. Our table comes from the stock app analysis, so you will need your own (`PdcBars.LevelToCm`): a cardboard box
  at known distances behind the car is enough.
- **Colours:** level 1 red, 2-6 yellow, 7-9 green.
- **Animation:** levels arrive in 15-20 cm steps. `PdcBars.Anim` makes each bar glide exponentially (time constant
  90 ms, about 0.25 s to settle); a sector that turns on appears directly at its place.
- **Projection:** `GroundProjector.bars(cam, bars, colors)` gives polygons in image pixels. Both edges of each band are
  projected every 5 cm; parts outside the image or beyond 95 degrees from the optical axis are cut.

```java
PdcBars.Anim anim = new PdcBars.Anim();
// when sensor data arrives (levels[4]: rear sectors left to right, closestCm: exact distance of the closest obstacle)
anim.set(levels, closestCm, myLevelToCm);
// on every frame of the rear camera
List<Integer> colors = new ArrayList<>();
List<float[]> polys = GroundProjector.bars(rearCam, anim.bars(car, SystemClock.uptimeMillis(), 1f), colors);
for (int i = 0; i < polys.size(); i++) {
    Path path = new Path();
    float[] p = polys.get(i);
    path.moveTo(mapX(p[0], p[1]), mapY(p[0], p[1]));          // image pixel -> your view (crop, scale, mirror)
    for (int k = 2; k < p.length; k += 2) path.lineTo(mapX(p[k], p[k + 1]), mapY(p[k], p[k + 1]));
    path.close();
    paint.setColor(colors.get(i));
    canvas.drawPath(path, paint);
}
if (anim.moving()) invalidate();
```

Remember the rear camera image is not mirrored: if you show it like a mirror, mirror the x of the polygons too.

## 2. 3D view with the "bowl"

The problem with a plain plane is that everything above the ground gets smeared further and further away. The bowl
keeps a **flat floor** where distances must be right, then bends up into a **wall** so far objects land on it instead
of stretching to the horizon.

- **Shape:** rings around the body shaped like a rounded rectangle (superellipse of degree 6, because the car is long
  and narrow), starting 4 cm outside the body. Ring spacing grows with t^2 up to 11 m, so the mesh is dense near the car.
- **Height:** flat up to 1.5 m from the body, then `height = (d - 1500)^2 / 3000` mm (`flatMm` = 1500,
  `curveMm` = 1500). Rivela 360 has used these values since v2.08 beta 6; before that the floor went to 2.8 m with a
  gentler wall (`curveMm` = 3800). An even shorter and steeper setting (1.2 m / 1.0 m) was rejected because it would
  have cut the side view to about 7 m. Both values are parameters of `Bowl.build`, so you can tune them.
- **Which camera paints where:** side cameras beside the car, front/rear cameras ahead/behind it, and around each
  corner a smooth blend by angle (from 30% to 70% of the quarter turn). Each weight is also multiplied by the
  camera's validity, so it fades out before the lens circle and the image border, then the four are normalised.
  This avoids hard seams; objects right on a corner can still look doubled, which is unavoidable with this approach.
- **Mesh size:** 144 x 56 vertices (about 16 000 triangles) runs smoothly on the MT2712.
- **Drawing:** one draw call with `BowlShaders.VERTEX` and `FRAGMENT_RGBA` (or `FRAGMENT_YUV`), the four camera
  textures bound, and an orbit camera around the car centre (remember z is down: use up = (0, 0, -1) with
  `Matrix.setLookAtM`). Draw the car model afterwards, with depth test, on top of the bowl.

```java
FisheyeCamera[] cams = {left, right, front, rear};
Bowl bowl = Bowl.build(cams, car, 144, 56, Bowl.FLAT_MM, Bowl.CURVE_MM);
// upload bowl.vertices (stride Bowl.STRIDE floats) and bowl.indices once; rebuild only if the calibration changes
```

A longer explanation, with drawings, is on the Rivela 360 page
[PROJECTION-3D.en.md](https://github.com/andneg7/rivela360-mg4/blob/main/PROJECTION-3D.en.md).

## License

Contributed to this repository under its GPL-3.0 license. As the author, andneg7 keeps the right to use this code in
Rivela 360.
