package com.lironk.bleclient;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;

import com.lironk.blelib.main.BleOperation;

import java.util.UUID;


class Connect extends BleOperation {
    BluetoothDevice mDevice;
    Connect (BluetoothDevice device) {
        mDevice = device;
    }
}

class Disconnect extends BleOperation {

}

class DiscoverServices extends BleOperation {

}

class CharacteristicWrite extends BleOperation {
    UUID mCharacUUID;
    byte[] mValue;

    CharacteristicWrite (UUID characUUID, byte[] value) {
        mCharacUUID = characUUID;
        mValue = value;
    }
}

class CharacteristicRead extends BleOperation {
    BluetoothGattCharacteristic mCharacteristic;

    CharacteristicRead (BluetoothGattCharacteristic characteristic) {
        mCharacteristic = characteristic;
    }
}

class NotificationEnable extends BleOperation {
    UUID mCharacteristicUUID;
    UUID mDescriptorUUID;

    NotificationEnable (UUID characteristicUUID, UUID descriptorUUID) {
        mCharacteristicUUID = characteristicUUID;
        mDescriptorUUID = descriptorUUID;
    }
}

class MtuRequest extends BleOperation {
    int mMTU;

    MtuRequest (int mtu) {
        mMTU = mtu;
    }
}