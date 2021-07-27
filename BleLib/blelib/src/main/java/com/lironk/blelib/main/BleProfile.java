package com.lironk.blelib.main;

import java.util.UUID;

public class BleProfile {

    public final static int GATT_HEADER_SIZE = 3;
    public final static int DEFAULT_MTU = 20;

    public final static int HEADER_SIZE = 2;
    public final static int MTU = 512;
    public final static String SERVER_NAME = "BleServer";

    // Service UUID
    public final static UUID SERVER_UUID = UUID.fromString("2f82a784-55fc-451f-9e03-69e71490d9bf");

    // Client Characteristic Config Descriptor (for notifications)
    public final static UUID CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Readable data characteristic UUIDs (sensor->mobile)
    public final static UUID R_STATUS = UUID.fromString("9f63117d-680d-4ef5-9e64-92391cc37615");
    public final static UUID R_BANDWIDTH = UUID.fromString("58d1f439-4433-4c04-a909-eebb0c1b4a38");
    public final static UUID R_USER = UUID.fromString("4bb55b36-4918-4116-8359-4cd2e2393743");

    // Writable data characteristic UUIDs (mobile->sensor)
    public final static UUID W_COMMAND = UUID.fromString("8a5dbb99-6159-4972-81de-48780ef1ea0e");

    public final static UUID[] READABLE_CHARC_ARR = {R_STATUS, R_BANDWIDTH, R_USER};

    public final static UUID[] WRITABLE_CHARC_ARR = {W_COMMAND};
}
