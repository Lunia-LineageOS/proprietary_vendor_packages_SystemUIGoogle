package com.google.android.systemui.dreamliner;

public final class DockInfo {
    public int accessoryType;
    public String manufacturer;
    public String model;
    public String serialNumber;

    @Override
    public String toString() {
        return manufacturer + ", " + model + ", " + serialNumber + ", " + accessoryType;
    }
}
