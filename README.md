# webgpu-terrain-generation

3D Prodecural world generation using the new kotlin bindings from Google's implementation of WebGPU, Dawn.

![Screenshot of app](https://i.imgur.com/wmSdDSE.png)

## Build instructions

* Build the bindings using the instructions from [the Dawn repository's Android tools documentation](https://dawn.googlesource.com/dawn/+/refs/heads/main/tools/android/)

* Publish the library to mavenLocal. It should be in your ~/.m2 directory

* Install the app using Android Studio onto your device or any other way.

## Note

The app is likely to not work on Android emulators. This is due to buggy Vulkan support which may or may not be fixed.
