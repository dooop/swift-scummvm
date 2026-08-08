# Release AAR

Place the uploaded Android release artifact here as `scummvm-release.aar`, or
pass its location when building:

```bash
./gradlew :app:assembleRelease \
  -Pscummvm.releaseAar=/absolute/path/to/scummvm-release.aar
```

Debug builds do not use this file; they build against the local `:scummvm`
project instead.
