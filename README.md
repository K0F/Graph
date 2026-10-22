# Graph (`cz.kof.graph`)

Personal Android **Share-To** and local photo short-video generator powered by the [`K0F/gram`](https://github.com/K0F/gram) and [`K0F/dada`](https://github.com/K0F/dada) stone-oracle engine.

## Features
- **Android Share-To Target (`ACTION_SEND` & `ACTION_SEND_MULTIPLE`)**: Select 1..N photos in any Android gallery or file manager, tap **Share -> Graph**, and render a short video.
- **Direct Local Photo Picker**: Load the latest photos from `/sdcard/DCIM/Camera` (synced via Syncthing) or pick any images from device storage.
- **Deterministic Dada Stone Oracle (`libgraph_dada.so`)**: Uses the native ARM64 `dada` FNV-64 + splitmix64 oracle over `noise.png` for deterministic slide selection (`0.20s` / 5 frames per slide) or standard `0.44s` / `1.00s` montage cuts.
- **PAL SD 932x576@25 Hardware MP4 Renderer**: Center-crops and scales images, converts to BT.601 grayscale, renders the opening `kof26` title card over the noise stone, and muxes AAC music from `/sdcard/Music/gram/` into `/sdcard/DCIM/Graph/graph_<timestamp>.mp4`.
- **One-Tap Personal Sharing**: Share the rendered `.mp4` directly to **WhatsApp Channels / Status**, **Personal Facebook**, or any app via Android's native share sheet.

## Build & Install (No Gradle Required)

Requires Android SDK (`build-tools/34.0.0`, `platforms/android-34`, `ndk/27.3.13750724`) and `javac`:

```bash
make apk       # builds build/Graph.apk
make install   # installs via adb onto connected phone and grants media permissions
make run       # launches cz.kof.graph/.MainActivity on the phone
```
