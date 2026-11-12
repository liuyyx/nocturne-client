package dev.nocturne.client.render;

/** 在 Camera#setup 完成后修改相机状态的模块接缝。 */
public interface CameraOverride {
    void applyCamera(Object camera);
}
