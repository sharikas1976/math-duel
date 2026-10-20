# How the interactive wall sensor works (RK3399 unit, Android 7.1.2)

Findings from the device report (Wall Inspector) and the supplier app `com.myt.mytclient` ("Capture_S", v4.7).

## Hardware
- IR/visible USB camera: Sonix "USB 2.0 Camera" (vid 0x0c45, pid 0x6361).
- Android sees **no touch input device**. Wall hits never arrive as Android touch events.

## Software path
1. `com.myt.mytclient.camera.CameraService` grabs camera frames (640x360 grey).
2. The frame is warped to the projected area using the supplier's own 4-corner calibration
   (`GetFourCornersActivity`, settings `CameraSetting/1_X..4_Y`), with optional flips.
3. Default algorithm `FrameDifferenceAlgorithm`: subtracts the frame from 2 frames earlier
   (`Core.subtract`, so only areas that got **brighter** count), thresholds it
   (`sensitivity`), drops blobs smaller than `area`.
4. The image is split into an **8 x 4 grid**; at most **one point per cell** is kept
   (the first blob centre in that cell).
5. Points are sent as **TUIO 1.1 / OSC over UDP to 127.0.0.1:3333**, every frame,
   with a **new session id for every point** (no tracking between frames).

## Consequences for our games
- It is motion sensing, not touch: arms, bodies, a moving ball and on-screen brightness
  changes all produce points. Keep visuals calm where players aim; ignore hits briefly
  after a target changes.
- One physical touch produces a stream of new ids: merge points that are close in space
  and time into one tap.
- Precision is about one grid cell (1/8 width x 1/4 height). Use big, well separated targets.
- The reported point is the centre of the moving area (hand + part of the arm).

## Starting the camera without a supplier game
`CameraService` is exported. Normally it is only started when a game is launched from the
supplier menu. Our app starts it directly:

```java
Intent i = new Intent();
i.setComponent(new ComponentName("com.myt.mytclient", "com.myt.mytclient.camera.CameraService"));
startService(i);
```

## Supplier game list
Capture_S treats packages named `com.MYT.game<N>` as games (`N` 10001-10100 = supplier
content packs, `N` 1-10000 = single games launched directly). An app with such a package
name would appear in the supplier's game menu.
