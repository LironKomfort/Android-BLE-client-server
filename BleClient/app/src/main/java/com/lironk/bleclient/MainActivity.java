package com.lironk.bleclient;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.lironk.blelib.characteristic.readable.BleCharcBandwidth;
import com.lironk.blelib.characteristic.readable.BleCharcAuthor;
import com.lironk.blelib.characteristic.readable.BleCharcStatus;
import com.lironk.blelib.characteristic.writable.BleCharcCommand;
import com.lironk.blelib.enums.eBleCommand;
import com.lironk.blelib.enums.eBleStatus;
import com.lironk.blelib.main.BleCharacteristic;
import com.lironk.blelib.main.BleOperation;
import com.lironk.blelib.main.IBleEvents;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.lironk.blelib.main.BleProfile.R_BANDWIDTH;
import static com.lironk.blelib.main.BleProfile.R_USER;
import static com.lironk.blelib.main.BleProfile.R_STATUS;
import static com.lironk.blelib.main.Utils.bleStateToString;


public class MainActivity extends AppCompatActivity implements IBleEvents {

    private final String TAG = "BleClient";
    private final int LOCATION_PERMISSION = 66;

    private BleClient mBleClient;

    private AtomicBoolean mBleConnected;
    private AtomicBoolean mStarted;

    private AtomicInteger mPacketCount;
    private AtomicInteger mByteCount;

    private long mStartTime;
    private long mEndTime;

    private ExecutorService mOutMsgExecutor;

    // Bluetooth API
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;

    private Handler mHandler;

    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            mHandler.postDelayed(this, 1000);
            TextView txtPackets = findViewById(R.id.txtPackets);
            txtPackets.setText(mPacketCount.get() + "");
            TextView txtBytes = findViewById(R.id.txtBytes);
            txtBytes.setText(mByteCount.get() + "");
            TextView sec = findViewById(R.id.txtDuration);
            long tock = System.currentTimeMillis() - mStartTime;
            sec.setText(tock + "");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mBleConnected = new AtomicBoolean(false);
        mStarted = new AtomicBoolean(false);

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
        mPacketCount = new AtomicInteger(0);
        mByteCount = new AtomicInteger(0);

        mHandler = new Handler();

        mOutMsgExecutor = Executors.newSingleThreadExecutor();

        mBleClient = new BleClient(this);
        mBleClient.addBTMessageListener(this);

        initBtnEvent();
    }

    private void initBtnEvent(){
        Button btnConnect = findViewById(R.id.btnBleConnect);
        btnConnect.setOnClickListener(v -> {
            if(mBleConnected.get()){
                mBleClient.disconnect();
            }
            else{
                mBleClient.connect();
            }
        });

        Button btnStart = findViewById(R.id.btnStart);
        btnStart.setOnClickListener(v -> {
            long tId = Thread.currentThread().getId();
            if(mBleConnected.get()){
                if(mStarted.get()){
//                    Log.d(TAG, "Thread: " + tId + ". Button Stop click event");
//                    send(new BleCharcCommand(new byte[]{eBleCommand.eCuBleCommand_Stop.getCommand()}));
                }
                else {
                    Log.d(TAG, "Thread: " + tId + ". Button Start click event");
                    send(new BleCharcCommand(new byte[]{eBleCommand.eCuBleCommand_Start.getCommand()}));
                }
            }
            else {
                Log.w(TAG, "Bluetooth not connected");
                showToast("Bluetooth not connected");
            }
        });

        Button btnGet = findViewById(R.id.btnGet);
        btnGet.setOnClickListener(v -> {
            if(mBleConnected.get()){
                if(mStarted.get()){
                    Log.w(TAG, "Bandwidth test in progress...");
                    showToast("Bandwidth test in progress...");
                }
                else{
                    send(new BleCharcCommand(new byte[]{eBleCommand.eCuBleCommand_Get.getCommand()}));
                }
            }
            else {
                Log.w(TAG, "Bluetooth not connected");
                showToast("Bluetooth not connected");
            }
        });
    }

    private void showToast(String txt){
        runOnUiThread(() -> Toast.makeText(this, txt, Toast.LENGTH_LONG).show());
    }

    private void send(BleCharacteristic charc){
        mOutMsgExecutor.execute(()->mBleClient.sendMessage(charc));
    }

    @Override
    public void bleConnectionStateChanged(int state) {
        String stateStr = bleStateToString(state);
        Log.d(TAG, "bleConnectionStateChanged. state=" + stateStr);
        switch (state){
            case BluetoothProfile.STATE_CONNECTED:
                mBleConnected.set(true);
                updateConnectBtnTxt();
                updateStartBtnText();
                break;
            case BluetoothProfile.STATE_DISCONNECTED:
                mBleConnected.set(false);
                mStarted.set(false);
                mHandler.removeCallbacks(mRunnable);
                initBandwidth();
                initAuthor();
                updateBandwidth();
                updateConnectBtnTxt();
                updateStartBtnText();
                break;
            default:
                mBleConnected.set(false);
        }

        runOnUiThread(() -> {
            TextView tv = findViewById(R.id.txtBleState);
            tv.setText(stateStr);
        });
    }

    @Override
    public void bleDataReceived(UUID uuid, byte[] data) {
        long tId = Thread.currentThread().getId();

        if(R_STATUS.equals(uuid)){
            Log.d(TAG, "Thread: " + tId + ". characteristicReceived. Status");
            BleCharcStatus status = new BleCharcStatus(data);
            handleStatus(status);
        }
        else if(R_BANDWIDTH.equals(uuid)){
            BleCharcBandwidth bytes = new BleCharcBandwidth(data);
            mPacketCount.set(mPacketCount.get() + 1);
            mByteCount.set(mByteCount.get() + bytes.getData().length + mBleClient.getHeaderSize());
        }
        else if(R_USER.equals(uuid)){
            Log.d(TAG, "characteristicReceived. Author");
            BleCharcAuthor Author = new BleCharcAuthor(data);
            runOnUiThread(() -> {
                TextView txtUser = findViewById(R.id.txtAuthor);
                txtUser.setText(Author.getName() + ", " + Author.getRole());
            });
        }
    }

    @Override
    public void bleDataSent(BleOperation operation) {

    }

    private void initBandwidth(){
        setBwTxt("");
        mPacketCount.set(0);
        mByteCount.set(0);
        mStartTime = System.currentTimeMillis();
        mEndTime = 0;
    }

    private void initAuthor(){
        runOnUiThread(() -> {
            TextView txtUser = findViewById(R.id.txtAuthor);
            txtUser.setText("N/A");
        });
    }

    private void updateBandwidth(){
        runOnUiThread(() -> {
            TextView txtPackets = findViewById(R.id.txtPackets);
            txtPackets.setText(mPacketCount.get() + "");
            TextView txtBytes = findViewById(R.id.txtBytes);
            txtBytes.setText(mByteCount.get() + "");
            TextView sec = findViewById(R.id.txtDuration);
            sec.setText(mEndTime + "");
        });
    }

    private void updateConnectBtnTxt(){
        runOnUiThread(() -> {
            if(mBleConnected.get()){
                // Disconnect txt
                Button btnStart = findViewById(R.id.btnBleConnect);
                btnStart.setText("DISCONNECT");
            }
            else{
                // Connect txt
                Button btnStart = findViewById(R.id.btnBleConnect);
                btnStart.setText("CONNECT");
            }
        });
    }

    private void updateStartBtnText(){
        runOnUiThread(() -> {
            Button btnStart = findViewById(R.id.btnStart);
            btnStart.setEnabled(!mStarted.get());
//            if(mStarted.get()){
//                Button btnStart = findViewById(R.id.btnStart);
//                btnStart.setEnabled(!mStarted.get());
//                btnStart.setText("STOP TX");
//            }
//            else{
//                // Start txt
//                Button btnStart = findViewById(R.id.btnStart);
//                btnStart.setText("START TX");
//            }
        });
    }

    private void startHandler(){
        mHandler.postDelayed(mRunnable, 0);
    }

    private void handleStatus(BleCharcStatus status){
        long tId = Thread.currentThread().getId();

        Log.d(TAG, "Thread: " + tId + ". handleStatus");
        if(status.getCommand() == eBleCommand.eCuBleCommand_Start){
            if(eBleStatus.eBleStatus_Ok.and(status.getStatus())){
                initBandwidth();
                mStarted.set(true);
                updateStartBtnText();
                startHandler();
                Log.d(TAG, "Thread: " + tId + ". Tx started");
            }
            else if(eBleStatus.eBleStatus_WrongState.and(status.getStatus())){
                Log.w(TAG, "Start Error. Wrong state");
                showToast("Start Error. Wrong state");
            }
            else {
                Log.w(TAG, "Start Error");
                showToast("Start Error");
            }
        }
        else if(status.getCommand() == eBleCommand.eCuBleCommand_Stop){
            if(eBleStatus.eBleStatus_Ok.and(status.getStatus())){
                mStarted.set(false);

                mEndTime = System.currentTimeMillis() - mStartTime;
                mHandler.removeCallbacks(mRunnable);

                updateStartBtnText();
                updateBandwidth();

                float seconds = mEndTime /1000f;
                float bandwidth = ((mByteCount.get()/seconds)/1000);
                String bandwidthStr = String.format("%.2f", bandwidth);

                Log.d(TAG, "Thread: " + tId + ". Tx stopped. Bandwidth: " + bandwidthStr + " KB/s");

                setBwTxt(bandwidthStr);
            }
            else if(eBleStatus.eBleStatus_WrongState.and(status.getStatus())){
                Log.w(TAG, "Stop Error. Wrong state");
                showToast("Stop Error. Wrong state");
            }
            else {
                Log.w(TAG, "Stop Error");
                showToast("Stop Error");
            }
        }
        else if(status.getCommand() == eBleCommand.eCuBleCommand_Get){
            if(eBleStatus.eBleStatus_Ok.and(status.getStatus())){
                Log.d(TAG, "Got user");
            }
            else {
                Log.w(TAG, "Get Error");
            }
        }
    }

    private void setBwTxt(String str){
        runOnUiThread(() -> {
            TextView txt = findViewById(R.id.txtBW);
            if(str.isEmpty()){
                txt.setText(str);
            }
            else{
                txt.setText(str + "  KB/s");
            }
        });
    }

    private void term(){
        mStarted.set(false);

        if(mHandler != null){
            mHandler.removeCallbacks(mRunnable);
            mHandler = null;
        }

        mBleClient.clearListenersList();
        mBleClient.term();
    }
}
