# Android-BLE-bandwidth client-server

Client-Server Android applications used to measure BLE bandwidth.
The Server sends to the client packets of size MTU for one minute in order to calculate BLE bandwidth.

BleLib is the common library used by both Client & Server apps.

Run BleServer application on a device running Android 7.1 & above.
Run BleClient application on a device running Android 7.1 & above.

How to use:
- Open Client app on one Android device
- Open Server app on a different Android device
- Click 'CONNECT' (client)
- Wait for the state to change to 'Connected'
- Click 'START TX'
- Server starts sending packets (MTU size) to the client during a minute
- Bandwidth calculation updates on the Client screen



