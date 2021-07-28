package com.lironk.bleserver;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.pm.PackageManager;
import android.os.Bundle;

import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.lironk.blelib.characteristic.readable.BleCharcAuthor;
import com.lironk.blelib.main.BleCharacteristic;
import com.lironk.blelib.main.BleOperation;
import com.lironk.bleserver.server.BleServer;
import com.lironk.blelib.characteristic.readable.BleCharcBandwidth;
import com.lironk.blelib.characteristic.readable.BleCharcStatus;
import com.lironk.blelib.characteristic.writable.BleCharcCommand;
import com.lironk.blelib.enums.eBleCommand;
import com.lironk.blelib.enums.eBleStatus;
import com.lironk.blelib.main.BleProfile;
import com.lironk.blelib.main.IBleEvents;

import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.lironk.blelib.main.Utils.bleStateToString;

public class MainActivity extends AppCompatActivity implements IBleEvents {

    private final String TAG = Class.class.getSimpleName();

    private final int ONE_SECOND_MS = 1000;
    private final int ONE_MIN_MS = ONE_SECOND_MS * 60;
    private final int LOCATION_PERMISSION = 66;

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;

    private BleServer mBleServer;
    private BleCharcBandwidth mBandwidthCharc;
    private ExecutorService mMsgExecutor;

    private final String mBandwidthTimerLock = "TimerLock";
    private Timer mBandwidthTimer;
    private AtomicBoolean mStarted;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mStarted = new AtomicBoolean(false);
        mMsgExecutor = Executors.newSingleThreadExecutor();

        mBluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();

        // We can't continue without proper Bluetooth support
        if (!bluetoothSupported()) {
            finish();
        }
        else{
            if (!mBluetoothAdapter.isEnabled()) {
                showToast("Enabling Bluetooth");
                Log.d(TAG, "Enabling Bluetooth");
                mBluetoothAdapter.enable();
            }
        }

        requestLocationPermission();
    }

    @Override
    protected void onDestroy(){
        super.onDestroy();
        term();
    }

    private boolean bluetoothSupported() {
        if (mBluetoothAdapter == null) {
            showToast("Bluetooth not supported");
            Log.w(TAG, "Bluetooth not supported");
            return false;
        }

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            showToast("Bluetooth LE not supported");
            Log.w(TAG, "Bluetooth LE not supported");
            return false;
        }

        return true;
    }

    private void requestLocationPermission(){
        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION);
        }
        else {
            init();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == LOCATION_PERMISSION){
            if(grantResults[0] == PackageManager.PERMISSION_GRANTED){
                init();
            }
            else {
                requestLocationPermission();
            }
        }
    }

    private void init(){
        mBleServer = new BleServer(getApplicationContext());
        mBleServer.addBleMessageListener(this);

        mBleServer.init();

        runOnUiThread(() -> {
            TextView serverState = findViewById(R.id.txtServerState);
            serverState.setText("Server Started");
        });
    }

    private void showToast(String txt){
        runOnUiThread(() -> Toast.makeText(getApplicationContext(), txt, Toast.LENGTH_LONG).show());
    }

    private void send(BleCharacteristic charc){
        mMsgExecutor.execute(()->mBleServer.sendMessage(charc));
    }

    @Override
    public void bleConnectionStateChanged(int state) {
        String stateStr = bleStateToString(state);
        Log.d(TAG, "bleConnectionStateChanged. state=" + stateStr);

        if(state == BluetoothAdapter.STATE_DISCONNECTED){
            mStarted.set(false);
            synchronized (mBandwidthTimerLock){
                if(mBandwidthTimer != null){
                    mBandwidthTimer.cancel();
                    mBandwidthTimer = null;
                }
            }
        }

        runOnUiThread(() -> {
            TextView tv = findViewById(R.id.txtState);
            tv.setText(stateStr);
        });
    }

    @Override
    public void bleDataSent(BleOperation operation) {
        if(mStarted.get()){
            send(mBandwidthCharc);
        }
    }

    @Override
    public void bleDataReceived(UUID uuid, byte[] data) {
        long tId = Thread.currentThread().getId();
        if (BleProfile.W_COMMAND.equals(uuid)){
            BleCharcCommand commandCharacteristic = new BleCharcCommand(data);
            switch (commandCharacteristic.getCommand()) {
                case eCuBleCommand_Start:
                    Log.d(TAG, "Thread: " + tId + ". bleDataReceived. Start");
                    start();
                    break;
                case eCuBleCommand_Stop:
                    Log.d(TAG, "Thread: " + tId + ". bleDataReceived. Stop");
                    stop();
                    break;
                case eCuBleCommand_Get:
                    Log.d(TAG, "bleDataReceived. Get");
                    sendUser();
                    break;
                default:
                    Log.e(TAG, "bleDataReceived. Unknown command");
                    break;
            }
        }
    }

    private void sendUser(){
        send(new BleCharcAuthor(1, "Liron Komfort", "SW Engineer"));
    }

    private void start(){
        long tId = Thread.currentThread().getId();
        if (mBandwidthTimer == null){
            Log.d(TAG, "Thread: " + tId + ". start");
            mBandwidthCharc = new BleCharcBandwidth(mBleServer.getMtu());
            send(new BleCharcStatus(eBleCommand.eCuBleCommand_Start, eBleStatus.eBleStatus_Ok.getStatus()));
            mStarted.set(true);
            send(mBandwidthCharc);
            startBandwidthTimer();
        }
        else{
            byte errMask =  (byte)(eBleStatus.eBleStatus_Error.getStatus() | eBleStatus.eBleStatus_WrongState.getStatus());
            send(new BleCharcStatus(eBleCommand.eCuBleCommand_Start, errMask));
        }
    }

    private void stop(){
        long tId = Thread.currentThread().getId();
        Log.d(TAG, "Thread: " + tId + ". stop");
        cancelBandwidthTimer();
    }

    private void startBandwidthTimer(){
        synchronized (mBandwidthTimerLock){
            long tId = Thread.currentThread().getId();
            Log.d(TAG, "Thread: " + tId + ". startBandwidthTimer");
            mBandwidthTimer = new Timer();
            mBandwidthTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    long tId = Thread.currentThread().getId();
                    Log.d(TAG, "Thread: " + tId + ". BandwidthTimer expired");
                    cancelBandwidthTimer();
                }
            }, ONE_MIN_MS);
        }
    }

    private void cancelBandwidthTimer(){
        synchronized (mBandwidthTimerLock){
            long tId = Thread.currentThread().getId();
            Log.d(TAG, "Thread: " + tId + ". cancelBandwidthTimer");
            if(mBandwidthTimer != null){
                mStarted.set(false);
                mBandwidthTimer.cancel();
                mBandwidthTimer = null;
                send(new BleCharcStatus(eBleCommand.eCuBleCommand_Stop, eBleStatus.eBleStatus_Ok.getStatus()));
            }
        }
    }

    private void term(){
        if(mBandwidthTimer != null){
            mBandwidthTimer.cancel();
            mBandwidthTimer = null;
        }

        if(mMsgExecutor != null){
            mMsgExecutor.shutdown();
            mMsgExecutor = null;
        }

        mBleServer.clearListenersList();
        mBleServer.term();
    }
}