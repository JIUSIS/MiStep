# Rotation bridge JVM regression

Run `./run.ps1` after `onestep-host/build_probe.ps1`.

This executes the built `HostEntry` methods against fake WM objects. Local shims
provide reflection and logging only; they are outside `module/src/main/java` and
must never be packaged in the APK. Android drawing, Binder, Xposed hook dispatch,
OEM callbacks and input transforms are not simulated.

The checks cover entering from landscape with unchanged orientation, lock changes,
local token rotation, main-to-side handoff, independent token ownership, exit
cleanup, preservation of native transforms, failure rollback and display scope.
They supplement device verification; they cannot establish visual acceptance.

The runner also exercises HostEntry's native freeform layer bridge. The local
WindowManager shim retains LayoutParams' required inheritance and is allocated
without executing Android stub constructors. Checks cover exact host filtering,
bottom visible native root selection, invalid/hidden anchors, and WM transaction
reuse when the magnification tree must split or rejoin. The shim is test-only;
SurfaceFlinger order, OEM animation and real touch dispatch remain device tests.
