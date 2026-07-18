package com.jingxin.pandrive.view;

/**
 * 指南针视图的公共接口
 * CompassView（风格0）和 CompassViewMinimal（风格1）都实现此接口
 */
public interface ICompassView {
    void setAzimuth(float azimuth);
    void setLocation(double lat, double lon, double alt);
    void setNightMode(boolean nightMode);
    boolean isDegreeArea(float x, float y);
}
