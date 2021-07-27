package com.lironk.blelib.characteristic.readable;

import android.util.Log;

import com.lironk.blelib.main.BleCharacteristic;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

import static com.lironk.blelib.main.BleProfile.R_USER;


public class BleCharcAuthor extends BleCharacteristic {
    private final String ID_KEY = "id";
    private final String NAME_KEY = "name";
    private final String ROLE_KEY = "role";

    private JSONObject mVerJsonObj = new JSONObject();

    private int mId;
    private String mName;
    private String mRole;

    public BleCharcAuthor(int id, String name, String role) {
        super(R_USER);
        try {
            mVerJsonObj.put(ID_KEY, id);
            mVerJsonObj.put(NAME_KEY, name);
            mVerJsonObj.put(ROLE_KEY, role);
        } catch (JSONException e) {
            Log.e("BleCharcData", e.getLocalizedMessage());
        }
    }

    public BleCharcAuthor(byte [] data){
        super(R_USER);
        String jsonStr = new String(data, StandardCharsets.UTF_8);
        try {
            JSONObject userJsonObj = new JSONObject(jsonStr);
            mId = userJsonObj.getInt(ID_KEY);
            mName = userJsonObj.getString(NAME_KEY);
            mRole = userJsonObj.getString(ROLE_KEY);
        }
        catch (JSONException e){
            mId = -1;
            mName = "";
            mRole = "";
            Log.e("BleCharcUser. String=" + jsonStr, e.getLocalizedMessage());
        }
    }

    public int getId(){
        return mId;
    }

    public String getName(){
        return mName;
    }

    public String getRole(){
        return mRole;
    }

    @Override
    public byte[] serialize(){
        try {
            return mVerJsonObj.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e("BleCharcData. serialize.", e.getLocalizedMessage());
        }
        return new byte[0];
    }
}
