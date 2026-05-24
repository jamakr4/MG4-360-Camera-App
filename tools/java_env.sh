#!/usr/bin/env bash

resolve_gradle_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" && -x "${JAVA_HOME}/bin/jlink" ]]; then
    printf '%s\n' "${JAVA_HOME}"
    return 0
  fi

  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local version
    local candidate
    for version in 21 17; do
      candidate="$(/usr/libexec/java_home -v "${version}" 2>/dev/null || true)"
      if [[ -n "${candidate}" && -x "${candidate}/bin/java" && -x "${candidate}/bin/jlink" ]]; then
        printf '%s\n' "${candidate}"
        return 0
      fi
    done

    candidate="$(/usr/libexec/java_home 2>/dev/null || true)"
    if [[ -n "${candidate}" && -x "${candidate}/bin/java" && -x "${candidate}/bin/jlink" ]]; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  fi

  return 1
}

configure_gradle_java() {
  local resolved_java_home
  resolved_java_home="$(resolve_gradle_java_home)" || {
    echo "Could not find a usable JDK for Gradle. Install JDK 17 or newer." >&2
    return 1
  }

  export JAVA_HOME="${resolved_java_home}"
  export PATH="${JAVA_HOME}/bin:${PATH}"
  echo "Using JAVA_HOME=${JAVA_HOME}"
}
