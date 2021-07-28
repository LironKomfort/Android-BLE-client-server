package com.lironk.bleclient;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import com.lironk.blelib.enums.eBleCommand;
import com.lironk.blelib.main.BleCharacteristic;
import com.lironk.blelib.main.BleOperation;
import com.lironk.blelib.main.BleProfile;
import com.lironk.blelib.main.IBleEvents;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static com.lironk.blelib.main.BleProfile.DEFAULT_MTU;
import static com.lironk.blelib.main.BleProfile.GATT_HEADER_SIZE;
import static com.lironk.blelib.main.BleProfile.HEADER_SIZE;
import static com.lironk.blelib.main.BleProfile.MTU;
import static com.lironk.blelib.main.BleProfile.SERVER_NAME;

public class BleClient {

    private static final String TAG = "BleClient";

    private Context mContext;
    private int mMaxPayloadSize = DEFAULT_MTU;

    private ArrayList<Byte> mIncomingDataList;

    private ExecutorService mExecutorOut;
    private Semaphore mExecuteSem;

    private LinkedList<BleOperation> mOperationList;
    private BleOperation mPendingOperation;
    private Semaphore mQueueSem;

    private ArrayList<IBleEvents> mBleMessageListeners;
    private ReentrantLock mMsgListenersLock;

    // Bluetooth API
    private BluetoothGatt mBluetoothGatt;
    private BluetoothLeScanner mBluetoothLeScanner;

    public BleClient(Context context){
        mContext = context;

        mIncomingDataList = new ArrayList<>();

        mExecutorOut = Executors.newSingleThreadExecutor();
        mExecuteSem = new Semaphore(1);

        mOperationList = new LinkedList<>();
        mPendingOperation = null;
        mQueueSem = new Semaphore(Integer.MAX_VALUE);
        mQueueSem.drainPermits();

        mMsgListenersLock = new ReentrantLock();
        mBleMessageListeners = new ArrayList<>();

        mBluetoothLeScanner = BluetoothAdapter.getDefaultAdapter().getBluetoothLeScanner();

        mExecutorOut.execute(()->executeOperation());
    }

    public int getHeaderSize(){
        return HEADER_SIZE;
    }

    public void addBTMessageListener(IBleEvents listener) {
        mMsgListenersLock.lock();
        mBleMessageListeners.add(listener);
        mMsgListenersLock.unlock();
    }

    public void removeBTMessageListener(IBleEvents listener) {
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

    public void connect(){
        startScan();
    }

    public void disconnect(){
        Log.d(TAG, "disconnect");
        enqueueOperation(new Disconnect());
        mQueueSem.release();
    }

    public void sendMessage(BleCharacteristic characteristic) {
        sendCharacteristic(characteristic.getUUID(), characteristic.serialize());
    }

    private void sendCharacteristic(UUID uuid, byte [] data){
        long tId = Thread.currentThread().getId();
        Log.d(TAG, "Thread: " + tId + ". sendCharacteristic");

        int currentPacketCount = 1;
        int partsCount = ((data.length/(mMaxPayloadSize - HEADER_SIZE)) + 1);
        int buffIdx = 0;

        if(partsCount > 1){
            for(currentPacketCount = 1; currentPacketCount <= partsCount; currentPacketCount++){
                int bufSize = Math.min((mMaxPayloadSize - HEADER_SIZE), (data.length - buffIdx));
                byte[] payload = new byte[bufSize + HEADER_SIZE];
                payload[0] = (byte) currentPacketCount;
                payload[1] = (byte) partsCount;
                System.arraycopy(data, buffIdx, payload, HEADER_SIZE, bufSize);

                enqueueOperation(new CharacteristicWrite(uuid, payload));
                mQueueSem.release();
                buffIdx += bufSize;
            }
        }
        else {
            byte[] payload = new byte[data.length + HEADER_SIZE];
            payload[0] = (byte) currentPacketCount;
            payload[1] = (byte) partsCount;
            System.arraycopy(data, buffIdx, payload, HEADER_SIZE, data.length);
            enqueueOperation(new CharacteristicWrite(uuid, payload));
            mQueueSem.release();
        }
    }

    private void startScan(){
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();

        ScanFilter scanFilter = new ScanFilter.Builder().
                setServiceUuid(new ParcelUuid(BleProfile.SERVER_UUID)).build();

        mBluetoothLeScanner.startScan(Arrays.asList(scanFilter), settings, mLeScanCallback);
    }

    // Device scan callback.
    private ScanCallback mLeScanCallback =
            new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    Log.d(TAG, "onScanResult. Name = " + result.getDevice().getName() + ". Address = " + result.getDevice().getAddress());
                    if(result.getDevice().getName() != null && result.getScanRecord().getDeviceName().equals(SERVER_NAME)){
                        Log.d(TAG, "Device found");
                        stopScan();
                        enqueueOperation(new Connect(result.getDevice()));
                        mQueueSem.release();
                    }
                }

                @Override
                public void onScanFailed(int errorCode){
                    super.onScanFailed(errorCode);
                    Log.e(TAG, "onScanFailed. errorCode = " + errorCode);
                }
            };

    private void stopScan(){
        Log.d(TAG, "stopScan");
        mBluetoothLeScanner.stopScan(mLeScanCallback);
    }

    private void enqueueOperation(BleOperation operation) {
        synchronized (mOperationList){
            long tId = Thread.currentThread().getId();
            Log.d(TAG, "Thread: " + tId + ". enqueueOperation");
            mOperationList.addLast(operation);
        }
    }

    private void executeOperation() {
        while (true){
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

            //long tId = Thread.currentThread().getId();
            //Log.d(TAG, "Thread: " + tId + ". executeOperation 3");

            synchronized (mOperationList){
                if(mOperationList.size() > 0){
                    BleOperation operation = mOperationList.removeFirst();

                    mPendingOperation = operation;

                    if (operation instanceof Connect) {
                        BluetoothDevice device = ((Connect)operation).mDevice;
                        mBluetoothGatt = device.connectGatt(mContext, false, mGattCallback);
                    }
                    else if(operation instanceof Disconnect){
                        mBluetoothGatt.disconnect();
                    }
                    else if (operation instanceof MtuRequest) {
                        mBluetoothGatt.requestMtu(((MtuRequest)operation).mMTU);
                    }
                    else if (operation instanceof DiscoverServices) {
                        mBluetoothGatt.discoverServices();
                    }
                    else if (operation instanceof NotificationEnable) {
                        NotificationEnable op = (NotificationEnable)operation;
                        notificationEnable(op.mCharacteristicUUID, op.mDescriptorUUID);
                    }
                    else if (operation instanceof CharacteristicRead) {
                        mBluetoothGatt.readCharacteristic(((CharacteristicRead)operation).mCharacteristic);
                    }
                    else if (operation instanceof CharacteristicWrite) {
                        CharacteristicWrite op = (CharacteristicWrite)operation;
                        writeCharacteristic(op.mCharacUUID, op.mValue);
                    }
                }
            }
        }
    }

    private void endOperation() {
        long tId = Thread.currentThread().getId();
        Log.d(TAG, "Thread: " + tId + ". endOperation");
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

    private void clearGattCache(){
        try {
            Method localMethod = mBluetoothGatt.getClass().getMethod("refresh");
            if(localMethod != null) {
                localMethod.invoke(mBluetoothGatt);
            }
        } catch(Exception localException) {
            Log.d(TAG, "clearGattCache Error=" + localException.toString());
        }
    }

    private void writeCharacteristic(UUID characUUID, byte[] value) {
        long tId = Thread.currentThread().getId();

        //check mBluetoothGatt is available
        if (mBluetoothGatt == null) {
            Log.e(TAG, "No connection");
            return;
        }
        BluetoothGattService Service = mBluetoothGatt.getService(BleProfile.SERVER_UUID);
        if (Service == null) {
            Log.e(TAG, "Service not found!");
            return;
        }
        BluetoothGattCharacteristic charac = Service.getCharacteristic(characUUID);
        if (charac == null) {
            Log.e(TAG, "Characteristic not found!");
            return;
        }

        Log.d(TAG, "Thread: " + tId + ". writeCharacteristic. UUID = " + characUUID);

        charac.setValue(value);

        boolean status = mBluetoothGatt.writeCharacteristic(charac);
        if (!status) {
            Log.e(TAG, "Write failed");
        }
    }

    private void notificationEnable(UUID characteristicUUID, UUID descriptorUUID) {
        BluetoothGattService service = mBluetoothGatt.getService(BleProfile.SERVER_UUID);
        // Get the counter characteristic
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);

        // Enable notifications for this characteristic locally
        mBluetoothGatt.setCharacteristicNotification(characteristic, true);

        // Write on the config descriptor to be notified when the value changes
        BluetoothGattDescriptor descriptor =
                characteristic.getDescriptor(descriptorUUID);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        mBluetoothGatt.writeDescriptor(descriptor);
    }

    public void term() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.disconnect();
        mBluetoothGatt.close();
        mBluetoothGatt = null;

        if(mExecutorOut != null){
            mExecutorOut.shutdown();
            mExecutorOut = null;
        }
    }

    // Various callback methods defined by the BLE API.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            switch (newState){
                case STATE_DISCONNECTED:
                    Log.d(TAG, "onConnectionStateChange. State = STATE_DISCONNECTED");
                    resetOperations();
                    clearGattCache();
                    break;
                case STATE_CONNECTING:
                    Log.d(TAG, "onConnectionStateChange. State = STATE_CONNECTING");
                    break;
                case STATE_CONNECTED:
                    Log.d(TAG, "onConnectionStateChange. State = STATE_CONNECTED");
                    if (mPendingOperation instanceof Connect) {
                        endOperation();
                        enqueueOperation(new DiscoverServices());
                        mQueueSem.release();
                    }
                    break;
                case STATE_DISCONNECTING:
                    Log.d(TAG, "onConnectionStateChange. State = STATE_DISCONNECTING");
                    break;
            }

            notifyConnectionState(newState);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered");
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Handle the error
                return;
            }
            if (mPendingOperation instanceof DiscoverServices) {
                endOperation();
                enqueueOperation(new MtuRequest(MTU));
                mQueueSem.release();
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "onCharacteristicRead");
            if (mPendingOperation instanceof CharacteristicRead) {
                endOperation();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            long tId = Thread.currentThread().getId();

            Log.d(TAG, "Thread: " + tId + ". onCharacteristicWrite");

            if(characteristic.getUuid().toString().equals("8a5dbb99-6159-4972-81de-48780ef1ea0e")){
                if(characteristic.getValue()[2] == 0){
                    Log.d(TAG, "onCharacteristicWrite. status=" + status + ". START");
                }
                else if(characteristic.getValue()[2] == 1){
                    Log.d(TAG, "onCharacteristicWrite. status=" + status + ". STOP");
                }
                else if(characteristic.getValue()[2] == 2){
                    Log.d(TAG, "onCharacteristicWrite. GET. status=" + status + ". GET");
                }
            }

            if (mPendingOperation instanceof CharacteristicWrite) {
                Log.d(TAG, "onCharacteristicWrite. END OPERATION");
                endOperation();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            // Notification CB
            //Log.d(TAG, "onCharacteristicChanged");
            long tId = Thread.currentThread().getId();

            byte[] value = characteristic.getValue();
            if(value != null && value.length >0){

                if(characteristic.getUuid().toString().equals("9f63117d-680d-4ef5-9e64-92391cc37615") && value[2] == eBleCommand.eCuBleCommand_Start.getCommand()){
                    Log.d(TAG, "Thread:" + tId + ". onCharacteristicChanged. COMMAND START");
                }
                else if(characteristic.getUuid().toString().equals("9f63117d-680d-4ef5-9e64-92391cc37615") && value[2] == eBleCommand.eCuBleCommand_Stop.getCommand()){
                    Log.d(TAG, "Thread:" + tId + ". onCharacteristicChanged. COMMAND STOP");
                }

                if(value.length > mMaxPayloadSize){
                    Log.e(TAG, "Thread: " + tId + ". onCharacteristicChanged. PAYLOAD SIZE=" + value.length
                            + ". WRONG PAYLOAD SIZE ON CHRC=" + characteristic.getUuid());
                }

                // Only one part - remove header & send CB
                if(value[1] == 1){
                    notifyMessageRcv(characteristic.getUuid(), Arrays.copyOfRange(value, HEADER_SIZE, value.length));
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
            }
            else{
                Log.w(TAG, "onCharacteristicChanged. Recv with 0 data");
            }

        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "onDescriptorWrite");
            if (mPendingOperation instanceof NotificationEnable) {
                endOperation();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d(TAG, "onMtuChanged, mtu=" + mtu);
            mMaxPayloadSize = mtu - GATT_HEADER_SIZE;
            if (mPendingOperation instanceof MtuRequest){
                endOperation();
                enqueueOperation(new NotificationEnable(BleProfile.R_STATUS, BleProfile.CLIENT_CONFIG));
                mQueueSem.release();
                enqueueOperation(new NotificationEnable(BleProfile.R_BANDWIDTH, BleProfile.CLIENT_CONFIG));
                mQueueSem.release();
                enqueueOperation(new NotificationEnable(BleProfile.R_USER, BleProfile.CLIENT_CONFIG));
                mQueueSem.release();
            }
        }
    };
}
