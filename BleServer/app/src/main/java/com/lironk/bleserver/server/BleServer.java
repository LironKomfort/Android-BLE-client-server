package com.lironk.bleserver.server;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;
import android.util.Log;

import com.lironk.blelib.enums.eBleCommand;
import com.lironk.blelib.main.BleCharacteristic;
import com.lironk.blelib.main.BleOperation;
import com.lironk.blelib.main.IBleEvents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

import static android.bluetooth.BluetoothGatt.GATT_FAILURE;
import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.content.Context.BLUETOOTH_SERVICE;
import static com.lironk.blelib.main.BleProfile.CLIENT_CONFIG;
import static com.lironk.blelib.main.BleProfile.DEFAULT_MTU;
import static com.lironk.blelib.main.BleProfile.GATT_HEADER_SIZE;
import static com.lironk.blelib.main.BleProfile.HEADER_SIZE;
import static com.lironk.blelib.main.BleProfile.R_STATUS;
import static com.lironk.blelib.main.BleProfile.SERVER_NAME;
import static com.lironk.blelib.main.BleProfile.SERVER_UUID;
import static com.lironk.blelib.main.BleProfile.READABLE_CHARC_ARR;
import static com.lironk.blelib.main.BleProfile.WRITABLE_CHARC_ARR;

public class BleServer {

    private static final String TAG = "BleServer";

    private Context mContext;
    private int mMaxPayloadSize = DEFAULT_MTU;

    private Set<UUID> mWritableCharsSet;

    private ArrayList<Byte> mIncomingDataList;

    private ExecutorService mExecutorOut;
    private Semaphore mExecuteSem;

    private LinkedList<BleOperation> mOperationList;
    private BleOperation mPendingOperation;
    private Semaphore mQueueSem;

    private ArrayList<IBleEvents> mBleMessageListeners;
    private ReentrantLock mMsgListenersLock;

    private BluetoothDevice mRegisteredDevice;

    // Bluetooth API
    private BluetoothManager mBluetoothManager;
    private BluetoothGattServer mBluetoothGattServer;
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;

    public BleServer(Context context){
        mContext = context;

        mWritableCharsSet = new HashSet<>(Arrays.asList(WRITABLE_CHARC_ARR));

        mIncomingDataList = new ArrayList<>();

        mExecutorOut = Executors.newSingleThreadExecutor();
        mExecuteSem = new Semaphore(1);

        mOperationList = new LinkedList<>();
        mPendingOperation = null;
        mQueueSem = new Semaphore(Integer.MAX_VALUE);
        mQueueSem.drainPermits();

        mBleMessageListeners = new ArrayList<>();
        mMsgListenersLock = new ReentrantLock();

        mRegisteredDevice = null;
    }

    public void init(){
        mBluetoothManager = (BluetoothManager) mContext.getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();

        boolean isNameChanged = BluetoothAdapter.getDefaultAdapter().setName(SERVER_NAME);
        Log.d(TAG, "BLE server name = " + SERVER_NAME +". changed = " + isNameChanged);

        // Register for system Bluetooth events
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        mContext.registerReceiver(mBluetoothReceiver, filter);
        if (!bluetoothAdapter.isEnabled()) {
            Log.d(TAG, "Enabling Bluetooth");
            bluetoothAdapter.enable();
        } else {
            Log.d(TAG, "Bluetooth enabled. Starting services");
            mExecutorOut.execute(()->executeOperation());
            startService();
        }
    }

    private BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
            switch (state) {
                case BluetoothAdapter.STATE_ON:
                    startService();
                    break;
                case BluetoothAdapter.STATE_OFF:
                    stopService();
                    break;
                default:
            }
        }
    };

    public int getMtu(){
        return mMaxPayloadSize - HEADER_SIZE;
    }

    public void addBleMessageListener(IBleEvents listener) {
        mMsgListenersLock.lock();
        mBleMessageListeners.add(listener);
        mMsgListenersLock.unlock();
    }

    public void removeBleMessageListener(IBleEvents listener) {
        mMsgListenersLock.lock();
        mBleMessageListeners.remove(listener);
        mMsgListenersLock.unlock();
    }

    public void clearListenersList() {
        mMsgListenersLock.lock();
        mBleMessageListeners.clear();
        mMsgListenersLock.unlock();
    }

    private void notifyConnectionState(int state){
        mMsgListenersLock.lock();
        for (IBleEvents listener : mBleMessageListeners) {
            listener.bleConnectionStateChanged(state);
        }
        mMsgListenersLock.unlock();
    }

    private void notifyMessageRcv(UUID uuid, byte [] data){
        mMsgListenersLock.lock();
        for (IBleEvents listener : mBleMessageListeners){
            listener.bleDataReceived(uuid, data);
        }
        mMsgListenersLock.unlock();
    }

    private void notifyMessageSent(BleOperation operation){
        mMsgListenersLock.lock();
        for (IBleEvents listener : mBleMessageListeners){
            listener.bleDataSent(operation);
        }
        mMsgListenersLock.unlock();
    }

    public void sendMessage(BleCharacteristic characteristic) {
       sendCharacteristic(characteristic.getUUID(), characteristic.serialize(), mRegisteredDevice);
    }

    private void sendCharacteristic(UUID uuid, byte [] data, BluetoothDevice device){
        long tId = Thread.currentThread().getId();

        if(uuid.toString().equals(R_STATUS.toString())){
            if(data[0] == eBleCommand.eCuBleCommand_Start.getCommand()){
                Log.d(TAG, "Thread: " + tId + ". sendCharacteristic. COMMAND START. Q size = " + mOperationList.size()
                        + ". Sem = " + mQueueSem.availablePermits());
            }
            else if(data[0] == eBleCommand.eCuBleCommand_Stop.getCommand()){
                Log.d(TAG, "Thread: " + tId + ". sendCharacteristic. COMMAND STOP. Q size = " + mOperationList.size()
                        + ". Sem = " + mQueueSem.availablePermits());
            }
            else if(data[0] == eBleCommand.eCuBleCommand_Get.getCommand()){
                Log.d(TAG, "Thread: " + tId + ". sendCharacteristic. COMMAND GET. Q size = " + mOperationList.size()
                        + ". Sem = " + mQueueSem.availablePermits());
            }
        }

        int currentPacketCount = 1;
        int partsCount = 1;
        int buffIdx = 0;

        if(data.length != mMaxPayloadSize - HEADER_SIZE){
            partsCount = ((data.length/(mMaxPayloadSize - HEADER_SIZE)) + 1);
        }

        if(partsCount > 1){
            for(currentPacketCount = 1; currentPacketCount <= partsCount; currentPacketCount++){
                int bufSize = Math.min((mMaxPayloadSize - HEADER_SIZE), (data.length - buffIdx));
                byte[] payload = new byte[bufSize + HEADER_SIZE];
                payload[0] = (byte) currentPacketCount;
                payload[1] = (byte) partsCount;
                System.arraycopy(data, buffIdx, payload, HEADER_SIZE, bufSize);

                enqueueOperation(new BleServerOpNotify(uuid, payload, device));
                mQueueSem.release();
                buffIdx += bufSize;
            }
        }
        else {
            byte[] payload = new byte[data.length + HEADER_SIZE];
            payload[0] = (byte) currentPacketCount;
            payload[1] = (byte) partsCount;
            System.arraycopy(data, buffIdx, payload, HEADER_SIZE, data.length);
            enqueueOperation(new BleServerOpNotify(uuid, payload, device));
            mQueueSem.release();
        }
    }

    // Begin advertising over Bluetooth that this device is connectable and supports our Service
    private void startAdvertising() {
        BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();
        mBluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (mBluetoothLeAdvertiser == null) {
            Log.w(TAG, "Failed to create advertiser");
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(SERVER_UUID))
                .build();

        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();

        mBluetoothLeAdvertiser.startAdvertising(settings, data, scanResponse, mAdvertiseCallback);
    }

    // Initialize the GATT server instance with the services/characteristics from the Time Profile
    private void startServer() {
        mBluetoothGattServer = mBluetoothManager.openGattServer(mContext, mGattServerCallback);
        if (mBluetoothGattServer == null) {
            Log.w(TAG, "Unable to create GATT server");
            return;
        }

        mBluetoothGattServer.addService(createService());
    }

    private BluetoothGattService createService() {
        BluetoothGattService service = new BluetoothGattService(SERVER_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        for (UUID uuid : READABLE_CHARC_ARR){
            // Readable Data characteristic
            BluetoothGattCharacteristic dataR = new BluetoothGattCharacteristic(
                    uuid,
                    BluetoothGattCharacteristic.PROPERTY_READ |
                            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ);

            BluetoothGattDescriptor configDescriptor = new BluetoothGattDescriptor(CLIENT_CONFIG,
                    //Read/write descriptor
                    BluetoothGattDescriptor.PERMISSION_READ |
                            BluetoothGattDescriptor.PERMISSION_WRITE);

            dataR.addDescriptor(configDescriptor);
            service.addCharacteristic(dataR);
        }

        for (UUID uuid : WRITABLE_CHARC_ARR) {
            // Writeable Data characteristic
            BluetoothGattCharacteristic dataW = new BluetoothGattCharacteristic(
                    uuid,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);
            service.addCharacteristic(dataW);
        }

        return service;
    }

    public void startService(){
        Log.d(TAG, "startService");
        startAdvertising();
        startServer();
    }

    public void stopService(){
        Log.d(TAG, "stopService");
        mBluetoothGattServer.clearServices();
        stopServer();

        BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();
        if (bluetoothAdapter.isEnabled()) {
            stopAdvertising();
        }
    }

    // Stop Bluetooth advertisements
    private void stopAdvertising() {
        if (mBluetoothLeAdvertiser == null) return;
        Log.d(TAG, "LE Advertise Stopped");
        mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
    }

    // Shut down the GATT server
    private void stopServer() {
        if (mBluetoothGattServer == null) return;

        mBluetoothGattServer.close();
    }

    public void term(){
        stopService();
        stopService();

        if(mExecutorOut != null){
            mExecutorOut.shutdown();
            mExecutorOut = null;
        }
    }

    private void enqueueOperation(BleOperation operation) {
        synchronized (mOperationList){
            long tId = Thread.currentThread().getId();
            //Log.d(TAG, "Thread: " + tId + ". enqueueOperation");
            BleServerOpNotify op = (BleServerOpNotify) operation;
            if(op.getCharcUUID().toString().equals(R_STATUS.toString()) &&
                    op.getByteArrValue()[2] == eBleCommand.eCuBleCommand_Stop.getCommand()){
                Log.d(TAG, "Thread " + tId + ". enqueueOperation. STOP. Add first");
                mOperationList.addFirst(operation);
            }
            else{
                mOperationList.addLast(operation);
           }
        }
    }

    private void executeOperation() {
        while (true) {
            long tId = Thread.currentThread().getId();

            //Log.d(TAG, "executeOperation");
            try {
                mQueueSem.acquire();
            } catch (InterruptedException e) {
                continue;
            }
            //Log.d(TAG, "executeOperation 2");

            try {
                mExecuteSem.acquire();
            } catch (InterruptedException e) {
                continue;
            }
            //Log.d(TAG, "Thread " + tId + ". executeOperation 3");

            synchronized (mOperationList) {
                if(mOperationList.size() > 0){
                    BleOperation operation = mOperationList.removeFirst();

                    mPendingOperation = operation;

                    if (operation instanceof BleServerOpNotify) {
                        //Log.d(TAG, "executeOperation. BleServerOpNotify");
                        BleServerOpNotify op = (BleServerOpNotify) operation;

                        if (op.getByteArrValue() != null) {
                            if(op.getCharcUUID().toString().equals(R_STATUS.toString()) && op.getByteArrValue()[2] == eBleCommand.eCuBleCommand_Stop.getCommand()){
                                Log.d(TAG, "Thread: " + tId + ". executeOperation. notifyRegisteredDevices. COMMAND STOP. Clear list");
                                mOperationList.clear();
                                mQueueSem.drainPermits();
                            }
                            notifyRegisteredDevices(op.getCharcUUID(), op.getByteArrValue(), op.getDevice());
                        }
                    }
                }
            }
        }
    }

    private void endOperation() {
        mExecuteSem.release();
    }

    private void resetOperations(){
        synchronized (mOperationList){
            mOperationList.clear();
        }
        mPendingOperation = null;
        endOperation();
        mQueueSem.drainPermits();
    }

    // Callback to receive information about the advertisement process
    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.d(TAG, "LE Advertise Started.");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "LE Advertise Failed: "+errorCode);
        }
    };

    // Callback to handle incoming requests to the GATT server
    private BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            switch (newState){
                case BluetoothProfile.STATE_DISCONNECTED:
                    Log.d(TAG, "onConnectionStateChange. STATE_DISCONNECTED");
                    mRegisteredDevice = null;
                    resetOperations();
//                    stopService();
//                    startService();
                    break;
                case BluetoothProfile.STATE_CONNECTING:
                    Log.d(TAG, "onConnectionStateChange. STATE_CONNECTING");
                    break;
                case STATE_CONNECTED:
                    Log.d(TAG, "onConnectionStateChange. STATE_CONNECTED");
                    mRegisteredDevice = device;
                    break;
                case BluetoothProfile.STATE_DISCONNECTING:
                    Log.d(TAG, "onConnectionStateChange. STATE_DISCONNECTING");
                    break;
            }

            notifyConnectionState(newState);
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            Log.d(TAG, "onCharacteristicReadRequest");
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            long tId = Thread.currentThread().getId();

            Log.d(TAG, "Thread: " + tId + ". onCharacteristicWriteRequest. UUID=" + characteristic.getUuid());

            if(device.equals(mRegisteredDevice) && mWritableCharsSet.contains(characteristic.getUuid())){
                // Only one part - remove header & send CB
                if(value[1] == 1){
                    new Thread(() -> notifyMessageRcv(characteristic.getUuid(), Arrays.copyOfRange(value, HEADER_SIZE, value.length))).start();
                }
                // First part - init byte array & copy data (without header)
                else if(value[0] == 1){
                    mIncomingDataList = new ArrayList<>();
                    for (int i = HEADER_SIZE; i < value.length; i++){
                        mIncomingDataList.add(value[i]);
                    }
                }
                // Last part - copy to byte array (without header) & send CB
                else if(value[0] == value[1]){
                    for (int i = HEADER_SIZE; i < value.length; i++){
                        mIncomingDataList.add(value[i]);
                    }

                    byte[] result = new byte[mIncomingDataList.size()];
                    for(int i = 0; i < mIncomingDataList.size(); i++) {
                        result[i] = mIncomingDataList.get(i).byteValue();
                    }

                    notifyMessageRcv(characteristic.getUuid(), result);
                }
                // Accumulate parts to byte array (without header)
                else {
                    for (int i = HEADER_SIZE; i < value.length; i++){
                        mIncomingDataList.add(value[i]);
                    }
                }

                if(responseNeeded){
                    mBluetoothGattServer.sendResponse(device, requestId, GATT_SUCCESS, offset, value);
                }
            }
            else {
                // Invalid characteristic
                Log.e(TAG, "onCharacteristicWriteRequest. Invalid Characteristic = " + characteristic.getUuid());
                if (responseNeeded)
                    mBluetoothGattServer.sendResponse(device, requestId, GATT_FAILURE, 0, null);
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattDescriptor descriptor) {
            long tId = Thread.currentThread().getId();
            Log.d(TAG, "Thread: " + tId + ". onDescriptorReadRequest");
            if (CLIENT_CONFIG.equals(descriptor.getUuid())) {
                Log.d(TAG, "Config descriptor read");
                byte[] returnValue;
                if (mRegisteredDevice.equals(device)) {
                    returnValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                } else {
                    returnValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                }
                mBluetoothGattServer.sendResponse(device, requestId, GATT_SUCCESS, 0, returnValue);
            } else {
                Log.w(TAG, "Unknown descriptor read request");
                mBluetoothGattServer.sendResponse(device, requestId, GATT_FAILURE, 0, null);
            }
        }

        //If a client wants to be notified of any changes in the counter characteristic value,
        // it should write its intent on a config descriptor
        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            long tId = Thread.currentThread().getId();
            Log.d(TAG, "Thread: " + tId + ". onDescriptorWriteRequest. requestId = " + requestId);
            if (CLIENT_CONFIG.equals(descriptor.getUuid())) {
                if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                    Log.d(TAG, "Subscribe to notifications. device = " + device);
                } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                    Log.d(TAG, "Unsubscribe from notifications. device = " + device);
                }

                if (responseNeeded) {
                    Log.d(TAG, "onDescriptorWriteRequest. sending response. device id = " + device.getAddress() +
                            ". name = " + device.getName());
                    mBluetoothGattServer.sendResponse(device, requestId, GATT_SUCCESS, offset, value);
                }
            } else {
                Log.w(TAG, "Unknown descriptor write request");
                if (responseNeeded) {
                    mBluetoothGattServer.sendResponse(device, requestId, GATT_FAILURE, 0, null);
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            long tId = Thread.currentThread().getId();
            Log.d(TAG, "Thread: " + tId + ". onMtuChanged, mtu = " + mtu);
            mMaxPayloadSize = mtu - GATT_HEADER_SIZE;
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            long tId = Thread.currentThread().getId();
            //Log.d(TAG, "Thread: " + tId + ". onNotificationSent");
            if (mPendingOperation instanceof BleServerOpNotify) {
                String uuid = ((BleServerOpNotify)mPendingOperation).getCharcUUID().toString();
                if(uuid.equals(R_STATUS.toString())){
                    Log.d(TAG, "Thread: " + tId + ". onNotificationSent. START/STOP");
                }

                endOperation();
                notifyMessageSent(mPendingOperation);
            }
        }
    };

    private void notifyRegisteredDevices(UUID characUUID, byte[] val, BluetoothDevice device) {
        long tId = Thread.currentThread().getId();

        if(characUUID.toString().equals(R_STATUS.toString())){
            if(val[2] == eBleCommand.eCuBleCommand_Start.getCommand()){
                Log.d(TAG, "Thread: " + tId + ". notifyRegisteredDevices. COMMAND START");
            }
            else if(val[2] == eBleCommand.eCuBleCommand_Stop.getCommand()){
                Log.d(TAG, "Thread: " + tId + ". notifyRegisteredDevices. COMMAND STOP");
            }
        }

        BluetoothGattCharacteristic characteristic = mBluetoothGattServer.getService(SERVER_UUID).getCharacteristic(characUUID);
        if (device != null) {
            characteristic.setValue(val);
            mBluetoothGattServer.notifyCharacteristicChanged(device, characteristic, false);
        }
    }
}
