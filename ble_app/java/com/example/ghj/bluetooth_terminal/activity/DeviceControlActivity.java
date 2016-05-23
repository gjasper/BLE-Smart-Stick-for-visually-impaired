package com.example.ghj.bluetooth_terminal.activity;

import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.actionbarsherlock.app.ActionBar;
import com.example.ghj.bluetooth_terminal.DeviceData;
import com.example.ghj.bluetooth_terminal.GeofenceRemover;
import com.example.ghj.bluetooth_terminal.GeofenceRequester;
import com.example.ghj.bluetooth_terminal.GeofenceUtils;
import com.example.ghj.bluetooth_terminal.GeofenceUtils.REMOVE_TYPE;
import com.example.ghj.bluetooth_terminal.GeofenceUtils.REQUEST_TYPE;
import com.example.ghj.bluetooth_terminal.R;
import com.example.ghj.bluetooth_terminal.SimpleGeofence;
import com.example.ghj.bluetooth_terminal.SimpleGeofenceStore;
import com.example.ghj.bluetooth_terminal.Utils;
import com.example.ghj.bluetooth_terminal.bluetooth.DeviceConnector;
import com.example.ghj.bluetooth_terminal.bluetooth.DeviceListActivity;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.location.Geofence;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public final class DeviceControlActivity extends BaseActivity {

    double currentLatitude;
    double currentLongitude;
    float radius = (float) 10.0;

    //Control flag defined by a button in UI, used to enable storing data
    public boolean dataStorageEnabled = false;

    //Control flag defined by a button in UI
    public boolean onButton = false;

    //Control flag defined by a timer started on obstacle notification
    public boolean gfRegisterEnabled = true;

    //Minimum time between a notification and the a registration or another notification, in seconds
    public int gfRegisterEnableTime = 3;

    //Bluetooth declarations

    private static final String DEVICE_NAME = "DEVICE_NAME";

    private static String MSG_NOT_CONNECTED;
    private static String MSG_CONNECTING;
    private static String MSG_CONNECTED;

    private static DeviceConnector connector;
    private static BluetoothResponseHandler mHandler;

    // Настройки приложения
    private boolean hexMode, needClean;
    private String command_ending;
    private String deviceName;

    //Location declarations

    /*
     * Use to set an expiration time for a geofence. After this amount
     * of time Location Services will stop tracking the geofence.
     * Remember to unregister a geofence when you're finished with it.
     * Otherwise, your app will use up battery. To continue monitoring
     * a geofence indefinitely, set the expiration time to
     * Geofence#NEVER_EXPIRE.
     */


    private static final long GEOFENCE_EXPIRATION_IN_HOURS = 12;
    //private static final long GEOFENCE_EXPIRATION_IN_MILLISECONDS = GEOFENCE_EXPIRATION_IN_HOURS * DateUtils.HOUR_IN_MILLIS;
    private static final long GEOFENCE_EXPIRATION_IN_MILLISECONDS = Geofence.NEVER_EXPIRE;

    // Store the current request
    private REQUEST_TYPE mRequestType;

    // Store the current type of removal
    private REMOVE_TYPE mRemoveType;

    // Persistent storage for geofences
    private SimpleGeofenceStore mPrefs;

    // Store a list of geofences to add
    List<Geofence> mCurrentGeofences;

    // Add geofences handler
    private GeofenceRequester mGeofenceRequester;
    // Remove geofences handler
    private GeofenceRemover mGeofenceRemover;

    /*
     * Internal lightweight geofence objects for geofence 1 and 2
     */
    private SimpleGeofence mUIGeofence;

    // decimal formats for latitude, longitude, and radius
    private DecimalFormat mLatLngFormat;
    private DecimalFormat mRadiusFormat;

    /*
     * An instance of an inner class that receives broadcasts from listeners and from the
     * IntentService that receives geofence transition events
     */
    private GeofenceSampleReceiver mBroadcastReceiver;

    // An intent filter for the broadcast receiver
    private IntentFilter mIntentFilter;

    // Store the list of geofences to remove
    private List<String> mGeofenceIdsToRemove;

    public Context mainActivityContext;

    private LocationManager locationManager;

    private GpsStatus mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceManager.setDefaultValues(this, R.xml.settings_activity, false);

        if (mHandler == null) mHandler = new BluetoothResponseHandler(this);
        else mHandler.setTarget(this);

        MSG_NOT_CONNECTED = getString(R.string.msg_not_connected);
        MSG_CONNECTING = getString(R.string.msg_connecting);
        MSG_CONNECTED = getString(R.string.msg_connected);

        setContentView(R.layout.activity_terminal);
        if (isConnected() && (savedInstanceState != null)) {
            setDeviceName(savedInstanceState.getString(DEVICE_NAME));
        } else getSupportActionBar().setSubtitle(MSG_NOT_CONNECTED);

        // Create a location manager to display latitude and longitude in real time on the UI
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // Create a variable to control the minimum time between location update requests
        int timeIntervalInMilliseconds = 1000;
        // Create a variable to control the minimum distance between location update requests
        float distanceIntervalInMeters = 0;

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, timeIntervalInMilliseconds, distanceIntervalInMeters, onLocationChanged);

        // Set the pattern for the latitude and longitude format
        String latLngPattern = getString(R.string.lat_lng_pattern);

        // Set the format for latitude and longitude
        mLatLngFormat = new DecimalFormat(latLngPattern);

        // Localize the format
        mLatLngFormat.applyLocalizedPattern(mLatLngFormat.toLocalizedPattern());

        // Set the pattern for the radius format
        String radiusPattern = getString(R.string.radius_pattern);

        // Set the format for the radius
        mRadiusFormat = new DecimalFormat(radiusPattern);

        // Localize the pattern
        mRadiusFormat.applyLocalizedPattern(mRadiusFormat.toLocalizedPattern());

        // Create a new broadcast receiver to receive updates from the listeners and service
        mBroadcastReceiver = new GeofenceSampleReceiver();

        // Create an intent filter for the broadcast receiver
        mIntentFilter = new IntentFilter();

        // Action for broadcast Intents that report successful addition of geofences
        mIntentFilter.addAction(GeofenceUtils.ACTION_GEOFENCES_ADDED);

        // Action for broadcast Intents that report successful removal of geofences
        mIntentFilter.addAction(GeofenceUtils.ACTION_GEOFENCES_REMOVED);

        // Action for broadcast Intents containing various types of geofencing errors
        mIntentFilter.addAction(GeofenceUtils.ACTION_GEOFENCE_ERROR);


        mIntentFilter.addAction(GeofenceUtils.ACTION_GEOFENCE_TRANSITION);

        // Instantiate a new geofence storage area
        mPrefs = new SimpleGeofenceStore(this);

        // Instantiate the current List of geofences
        mCurrentGeofences = new ArrayList<Geofence>();

        // Instantiate a Geofence requester
        mGeofenceRequester = new GeofenceRequester(this);

        // Instantiate a Geofence remover
        mGeofenceRemover = new GeofenceRemover(this);

        mainActivityContext = this;
    }


    private LocationListener onLocationChanged = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {

            currentLatitude = location.getLatitude();
            currentLongitude = location.getLongitude();

        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {

        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }


    };

    /**
     * Проверка готовности соединения
     */
    private boolean isConnected() {
        return (connector != null) && (connector.getState() == DeviceConnector.STATE_CONNECTED);
    }

    /**
     * Разорвать соединение
     */
    private void stopConnection() {
        if (connector != null) {
            connector.stop();
            connector = null;
            deviceName = null;
        }
    }

    /**
     * Список устройств для подключения
     */
    private void startDeviceListActivity() {
        stopConnection();
        Intent serverIntent = new Intent(this, DeviceListActivity.class);
        startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
    }

    /**
     * Обработка аппаратной кнопки "Поиск"
     *
     * @return
     */
    @Override
    public boolean onSearchRequested() {
        if (super.isAdapterReady()) startDeviceListActivity();
        return false;
    }
    // ==========================================================================

    @Override
    public boolean onCreateOptionsMenu(com.actionbarsherlock.view.Menu menu) {
        getSupportMenuInflater().inflate(R.menu.device_control_activity, menu);
        return true;
    }
    // ============================================================================

    @Override
    public boolean onOptionsItemSelected(com.actionbarsherlock.view.MenuItem item) {
        switch (item.getItemId()) {

            case R.id.menu_search:
                if (super.isAdapterReady()) {
                    if (isConnected()) stopConnection();
                    else startDeviceListActivity();
                } else {
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                }
                return true;

            case R.id.menu_settings:
                final Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
                return true;

            // Remove all geofences from storage
            case R.id.menu_item_clear_geofence_history:
                mPrefs.clearAllGeofences();
                unregisterByPendingIntent();
                return true;


            case R.id.register_geofence:
                registerFromCurrentLocation();
                return true;

            case R.id.store_data:
                enableDataStorage();
                return true;

            // Pass through any other request
            default:
                return super.onOptionsItemSelected(item);
        }
    }
    // ============================================================================

    @Override
    public void onStart() {
        super.onStart();

        // hex mode
        final String mode = Utils.getPrefence(this, getString(R.string.pref_commands_mode));
        this.hexMode = mode.equals("HEX");

        // Окончание строки
        this.command_ending = getCommandEnding();

        // Формат отображения лога команд

        this.needClean = Utils.getBooleanPrefence(this, getString(R.string.pref_need_clean));
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, mIntentFilter);
        refreshOnButtonState();
    }

    /**
     * Получить из настроек признак окончания команды
     */
    private String getCommandEnding() {
        String result = Utils.getPrefence(this, getString(R.string.pref_commands_ending));
        if (result.equals("\\r\\n")) result = "\r\n";
        else if (result.equals("\\n")) result = "\n";
        else if (result.equals("\\r")) result = "\r";
        else result = "";
        return result;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    String address = data.getStringExtra(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
                    BluetoothDevice device = btAdapter.getRemoteDevice(address);
                    if (super.isAdapterReady() && (connector == null)) setupConnector(device);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                super.pendingRequestEnableBt = false;
                if (resultCode != Activity.RESULT_OK) {
                    Utils.log("BT not enabled");
                }
                break;
            // If the request code matches the code sent in onConnectionFailed
            case GeofenceUtils.CONNECTION_FAILURE_RESOLUTION_REQUEST:

                switch (resultCode) {
                    // If Google Play services resolved the problem
                    case Activity.RESULT_OK:

                        // If the request was to add geofences
                        if (GeofenceUtils.REQUEST_TYPE.ADD == mRequestType) {

                            // Toggle the request flag and send a new request
                            mGeofenceRequester.setInProgressFlag(false);

                            // Restart the process of adding the current geofences
                            mGeofenceRequester.addGeofences(mCurrentGeofences);

                            // If the request was to remove geofences
                        } else if (GeofenceUtils.REQUEST_TYPE.REMOVE == mRequestType) {

                            // Toggle the removal flag and send a new removal request
                            mGeofenceRemover.setInProgressFlag(false);

                            // If the removal was by Intent
                            if (GeofenceUtils.REMOVE_TYPE.INTENT == mRemoveType) {

                                // Restart the removal of all geofences for the PendingIntent
                                mGeofenceRemover.removeGeofencesByIntent(
                                        mGeofenceRequester.getRequestPendingIntent());

                                // If the removal was by a List of geofence IDs
                            } else {

                                // Restart the removal of the geofence list
                                mGeofenceRemover.removeGeofencesById(mGeofenceIdsToRemove);
                            }
                        }
                        break;

                    // If any other result was returned by Google Play services
                    default:

                        // Report that Google Play services was unable to resolve the problem.
                        Log.d(GeofenceUtils.APPTAG, getString(R.string.no_resolution));
                }

                // If any other request code was received
            default:
                // Report that this Activity received an unknown requestCode
                Log.d(GeofenceUtils.APPTAG,
                        getString(R.string.unknown_activity_request_code, requestCode));

                break;
        }
    }

    /**
     * Установка соединения с устройством
     */
    private void setupConnector(BluetoothDevice connectedDevice) {
        stopConnection();
        try {
            String emptyName = getString(R.string.empty_device_name);
            DeviceData data = new DeviceData(connectedDevice, emptyName);
            connector = new DeviceConnector(data, mHandler);
            connector.connect();
        } catch (IllegalArgumentException e) {
            Utils.log("setupConnector failed: " + e.getMessage());
        }
    }

    /**
     * Добавление ответа в лог
     *
     * @param message - текст для отображения
     */
    void registerGeofenceFromBluetoothSignal(String message) {

        String registerMessage = "register";

        if (message.toLowerCase().contains(registerMessage.toLowerCase())) {
            registerFromCurrentLocation();
        }
        Toast.makeText(this, "Message received:" + message, Toast.LENGTH_SHORT).show();
    }

    void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
        getSupportActionBar().setSubtitle(deviceName);
    }

    /**
     * Обработчик приёма данных от bluetooth-потока
     */
    private static class BluetoothResponseHandler extends Handler {
        private WeakReference<DeviceControlActivity> mActivity;

        public BluetoothResponseHandler(DeviceControlActivity activity) {
            mActivity = new WeakReference<DeviceControlActivity>(activity);
        }

        public void setTarget(DeviceControlActivity target) {
            mActivity.clear();
            mActivity = new WeakReference<DeviceControlActivity>(target);
        }

        @Override
        public void handleMessage(Message msg) {
            DeviceControlActivity activity = mActivity.get();
            if (activity != null) {
                switch (msg.what) {
                    case MESSAGE_STATE_CHANGE:

                        Utils.log("MESSAGE_STATE_CHANGE: " + msg.arg1);
                        final ActionBar bar = activity.getSupportActionBar();
                        switch (msg.arg1) {
                            case DeviceConnector.STATE_CONNECTED:
                                bar.setSubtitle(MSG_CONNECTED);
                                break;
                            case DeviceConnector.STATE_CONNECTING:
                                bar.setSubtitle(MSG_CONNECTING);
                                break;
                            case DeviceConnector.STATE_NONE:
                                bar.setSubtitle(MSG_NOT_CONNECTED);
                                break;
                        }
                        break;

                    case MESSAGE_READ:
                        final String readMessage = (String) msg.obj;
                        if (readMessage != null) {
                            activity.registerGeofenceFromBluetoothSignal(readMessage);
                        }
                        break;

                    case MESSAGE_DEVICE_NAME:
                        activity.setDeviceName((String) msg.obj);
                        break;

                    case MESSAGE_WRITE:
                        // stub
                        break;

                    case MESSAGE_TOAST:
                        // stub
                        break;
                }
            }
        }
    }


    private boolean servicesConnected() {

        // Check that Google Play services is available
        int resultCode =
                GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);

        // If Google Play services is available
        if (ConnectionResult.SUCCESS == resultCode) {

            // In debug mode, log the status
            Log.d(GeofenceUtils.APPTAG, getString(R.string.play_services_available));

            // Continue
            return true;

            // Google Play services was not available for some reason
        } else {

            // Display an error dialog
            Dialog dialog = GooglePlayServicesUtil.getErrorDialog(resultCode, this, 0);
            if (dialog != null) {
                Toast.makeText(this, dialog.toString(), Toast.LENGTH_LONG).show();
            }
            return false;
        }
    }

    /**
     * Called when the user clicks the "Remove geofences" button
     *
     * @param view The view that triggered this callback
     */

    public void onUnregisterByPendingIntentClicked(View view) {
        unregisterByPendingIntent();
    }


    public void unregisterByPendingIntent() {
        /*
         * Remove all geofences set by this app. To do this, get the
         * PendingIntent that was added when the geofences were added
         * and use it as an argument to removeGeofences(). The removal
         * happens asynchronously; Location Services calls
         * onRemoveGeofencesByPendingIntentResult() (implemented in
         * the current Activity) when the removal is done
         */

        /*
         * Record the removal as remove by Intent. If a connection error occurs,
         * the app can automatically restart the removal if Google Play services
         * can fix the error
         */
        // Record the type of removal
        mRemoveType = GeofenceUtils.REMOVE_TYPE.INTENT;

        /*
         * Check for Google Play services. Do this after
         * setting the request type. If connecting to Google Play services
         * fails, onActivityResult is eventually called, and it needs to
         * know what type of request was in progress.
         */
        if (!servicesConnected()) {
            Toast.makeText(getApplicationContext(), R.string.unregister_error, Toast.LENGTH_SHORT).show();
            return;
        }

        // Try to make a removal request
        try {
        /*
         * Remove the geofences represented by the currently-active PendingIntent. If the
         * PendingIntent was removed for some reason, re-create it; since it's always
         * created with FLAG_UPDATE_CURRENT, an identical PendingIntent is always created.
         */
            mGeofenceRemover.removeGeofencesByIntent(mGeofenceRequester.getRequestPendingIntent());

        } catch (UnsupportedOperationException e) {
            // Notify user that previous request hasn't finished.
            Toast.makeText(this, R.string.remove_geofences_already_requested_error,
                    Toast.LENGTH_LONG).show();
        }
        Toast.makeText(getApplicationContext(), R.string.unregister_success, Toast.LENGTH_SHORT).show();

    }

    /**
     * Called when the user clicks the "Active geofences" button.
     * Shows an ArrayList of the active geofences currently stored in SharedPreferences
     */

    public void OnActiveGeofencesClicked(View view) {

        ArrayList<SimpleGeofence> arrayList = SimpleGeofence.getActiveGeofences(getApplicationContext());
        String dsc = "";

        Toast.makeText(this, " From arrayList" + arrayList.size() + " active geofences", Toast.LENGTH_SHORT).show();
        Toast.makeText(this, " From mPrefs" + mPrefs.getNumberOfStoredGeofences() + " active geofences", Toast.LENGTH_SHORT).show();


        for (SimpleGeofence anArrayList : arrayList) {
            dsc = "";
            dsc += "ID: " + anArrayList.getId() +
                    ", Lat: " + anArrayList.getLatitude() +
                    ", Long: " + anArrayList.getLongitude() +
                    ", Radius: " + anArrayList.getRadius();
            Toast.makeText(this, dsc, Toast.LENGTH_LONG).show();
        }
    }

    public void onBeginMonitoring(View view) {

        refreshOnButtonState();

    }

    public void registerFromCurrentLocation(View view) {

        registerFromCurrentLocation();

    }

    public void enableDataStorage() {
       dataStorageEnabled = !dataStorageEnabled;

        if(dataStorageEnabled) {
            Toast.makeText(getApplicationContext(), "Data storage enabled", Toast.LENGTH_SHORT).show();

        }else {
            Toast.makeText(getApplicationContext(), "Data storage disabled", Toast.LENGTH_SHORT).show();
        }
    }

    public void refreshOnButtonState() {

        onButton = ((ToggleButton)findViewById(R.id.begin_monitoring)).isChecked();

    }

    public void saveObstacleInFile(String id, double longitude, double latitude) {

        String estado = Environment.getExternalStorageState();
        String status = "";

        String tableTextToSave = "LONG: "+longitude+"LAT: "+latitude+"\n";
        String kmlTextToSave = "<Placemark>\n" +
                "\t\t <name>O"+id+"</name>\n" +
                "\t\t <Point>\n" +
                "\t\t\t<coordinates>"+longitude+","+latitude+",0</coordinates>\n" +
                "\t\t </Point>\n" +
                "\t</Placemark>\n";


        if (Environment.MEDIA_MOUNTED.equals(estado)) {
            try {
                File file = new File(getExternalFilesDir(null), "obstacles_data.txt");
                FileOutputStream fos = new FileOutputStream(file, true);
                fos.write((tableTextToSave).getBytes());
                fos.close();

                status = "Obstacle "+id+" saved successfully";
            } catch (Exception e) {
                status = "Erro ao armazenar: " + e.getMessage();
            } finally {
                Log.w("Log File", status);
                Toast.makeText(this, status, Toast.LENGTH_SHORT).show();
            }

            try {
                File file = new File(getExternalFilesDir(null), "obstacles_data.kml");
                FileOutputStream fos = new FileOutputStream(file, true);
                fos.write((kmlTextToSave).getBytes());
                fos.close();

                status = "Obstacle "+id+" saved successfully";
            } catch (Exception e) {
                status = "Erro ao armazenar: " + e.getMessage();
            } finally {
                Log.w("Log File", status);
                Toast.makeText(this, status, Toast.LENGTH_SHORT).show();
            }
        } else {
            Log.e("Log File", "Media não disponivel");
            Toast.makeText(this, "Media não disponivel", Toast.LENGTH_SHORT).show();
        }
    }

    public void saveNotificationInFile(String id, double longitude, double latitude) {

        String estado = Environment.getExternalStorageState();
        String status = "";

        String tableTextToSave = "LONG: "+longitude+"LAT: "+latitude+"\n";
        String kmlTextToSave = "<Placemark>\n" +
                "\t\t <name>"+id+"</name>\n" +
                "\t\t <Point>\n" +
                "\t\t\t<coordinates>"+longitude+","+latitude+",0</coordinates>\n" +
                "\t\t </Point>\n" +
                "\t</Placemark>\n";


        if (Environment.MEDIA_MOUNTED.equals(estado)) {
            try {
                File file = new File(getExternalFilesDir(null), "notifications_data.txt");
                FileOutputStream fos = new FileOutputStream(file, true);
                fos.write((tableTextToSave).getBytes());
                fos.close();

                status = "Notification "+id+" saved successfully";
            } catch (Exception e) {
                status = "Erro ao armazenar: " + e.getMessage();
            } finally {
                Log.w("Log File", status);
                Toast.makeText(this, status, Toast.LENGTH_SHORT).show();
            }

            try {
                File file = new File(getExternalFilesDir(null), "notifications_data.kml");
                FileOutputStream fos = new FileOutputStream(file, true);
                fos.write((kmlTextToSave).getBytes());
                fos.close();

                status = "Obstacle "+id+" saved successfully";
            } catch (Exception e) {
                status = "Erro ao armazenar: " + e.getMessage();
            } finally {
                Log.w("Log File", status);
                Toast.makeText(this, status, Toast.LENGTH_SHORT).show();
            }
        } else {
            Log.e("Log File", "Media não disponivel");
            Toast.makeText(this, "Media não disponivel", Toast.LENGTH_SHORT).show();
        }
    }
    
    public void registerFromCurrentLocation() {

        if (currentLongitude == 0 && currentLatitude == 0) {
            Toast.makeText(getApplicationContext(), R.string.registration_failed_no_geodata, Toast.LENGTH_LONG).show();
            return;
        }
        if (!onButton) {
            Toast.makeText(getApplicationContext(), R.string.registration_failed_monitoring_off, Toast.LENGTH_LONG).show();
            return;
        }else{
            if (!gfRegisterEnabled) {
                Toast.makeText(getApplicationContext(), R.string.registration_failed_obstacle_already_placed, Toast.LENGTH_LONG).show();
                return;
            }
        }


        /*
         * Record the request as an ADD. If a connection error occurs,
         * the app can automatically restart the add request if Google Play services
         * can fix the error
         */
        mRequestType = GeofenceUtils.REQUEST_TYPE.ADD;

        /*
         * Check for Google Play services. Do this after
         * setting the request type. If connecting to Google Play services
         * fails, onActivityResult is eventually called, and it needs to
         * know what type of request was in progress.
         */
        if (!servicesConnected()) {
            return;
        }

        String nextValidId = String.valueOf(mPrefs.getNextValidId());

        Log.d(GeofenceUtils.APPTAG, "Requesting create geofence with lat="+currentLatitude+" long="+currentLongitude+" radius="+radius);

        mUIGeofence = new SimpleGeofence(
                nextValidId, currentLatitude, currentLongitude, radius,
                // Set the expiration time
                GEOFENCE_EXPIRATION_IN_MILLISECONDS,
                // Only detect entry transitions
                Geofence.GEOFENCE_TRANSITION_ENTER);

        // Store this flat version in SharedPreferences
        mPrefs.setGeofence(String.valueOf(mPrefs.getNextValidId()), mUIGeofence);

        /*
         * Add Geofence objects to a List. toGeofence()
         * creates a Location Services Geofence object from a
         * flat object
         */
        mCurrentGeofences.add(mUIGeofence.toGeofence());

        // Start the request. Fail if there's already a request in progress
        try {
            // Try to add geofences
            mGeofenceRequester.addGeofences(mCurrentGeofences);
        } catch (UnsupportedOperationException e) {
            // Notify user that previous request hasn't finished.
            Toast.makeText(this, R.string.add_geofences_already_requested_error,
                    Toast.LENGTH_LONG).show();
        }

        Toast.makeText(mainActivityContext, R.string.register_success, Toast.LENGTH_SHORT).show();

        if(dataStorageEnabled) {
            saveObstacleInFile(nextValidId, currentLongitude, currentLatitude);
        }

    }

    public class setGfRegisterEnabled extends TimerTask{

        private final Context context;

        public setGfRegisterEnabled(Context con) {
            this.context = con;
        }

        @Override
        public void run() {
            Log.d(GeofenceUtils.APPTAG, "gfRegisterEnabled = true");
            gfRegisterEnabled = true;
        }
    }

    public class GeofenceSampleReceiver extends BroadcastReceiver {
        /*
         * Define the required method for broadcast receivers
         * This method is invoked when a broadcast Intent triggers the receiver
         */
        @Override
        public void onReceive(Context context, Intent intent) {

            // Check the action code and determine what to do
            String action = intent.getAction();


            // Intent contains information about errors in adding or removing geofences
            if (TextUtils.equals(action, GeofenceUtils.ACTION_GEOFENCE_ERROR)) {

                handleGeofenceError(context, intent);

                // Intent contains information about successful addition or removal of geofences
            } else if (
                    TextUtils.equals(action, GeofenceUtils.ACTION_GEOFENCES_ADDED)
                            ||
                            TextUtils.equals(action, GeofenceUtils.ACTION_GEOFENCES_REMOVED)) {

                handleGeofenceStatus(context, intent);

                // Intent contains information about a geofence transition
            } else if (TextUtils.equals(action, GeofenceUtils.ACTION_GEOFENCE_TRANSITION)) {


                if (gfRegisterEnabled && onButton)
                    handleGeofenceTransition(context, intent);

                // The Intent contained an invalid action
            } else {
                Log.e(GeofenceUtils.APPTAG, getString(R.string.invalid_action_detail, action));
                Toast.makeText(context, R.string.invalid_action, Toast.LENGTH_LONG).show();
            }
        }

        /**
         * If you want to display a UI message about adding or removing geofences, put it here.
         *
         * @param context A Context for this component
         * @param intent  The received broadcast Intent
         */
        private void handleGeofenceStatus(Context context, Intent intent) {

        }

        /**
         * Report geofence transitions to the UI
         *
         * @param context A Context for this component
         * @param intent  The Intent containing the transition
         */
        private void handleGeofenceTransition(Context context, Intent intent) {
            /*
             * If you want to change the UI when a transition occurs, put the code
             * here. The current design of the app uses a notification to inform the
             * user that a transition has occurred.
             */

            Vibrator vibrate;

            vibrate = (Vibrator) getSystemService(VIBRATOR_SERVICE);

            long[] vibrationPattern = {0, 100};

            if (vibrate != null) {
                vibrate.vibrate(vibrationPattern, 1);
            }

            gfRegisterEnabled = false;
            Log.d(GeofenceUtils.APPTAG, "gfRegisterEnabled = false");

            /*
            Intent speechIntent = new Intent();
            speechIntent.setClass(getApplicationContext(), ReadTheMessage.class);

            speechIntent.putExtra("MESSAGE", getString(R.string.obstacle_near));
            context.startService(speechIntent);
            */

            ToneGenerator toneG = new ToneGenerator(AudioManager.STREAM_DTMF, 100);
            toneG.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 750);

            Timer timer = new Timer();
            TimerTask setGfRegisterEnabled = new setGfRegisterEnabled(DeviceControlActivity.this);
            timer.schedule(setGfRegisterEnabled, gfRegisterEnableTime * 1000);


            if(dataStorageEnabled) {
                saveNotificationInFile("",currentLongitude,currentLatitude);
            }
        }

        /**
         * Report addition or removal errors to the UI, using a Toast
         *
         * @param intent A broadcast Intent sent by ReceiveTransitionsIntentService
         */
        private void handleGeofenceError(Context context, Intent intent) {
            String msg = intent.getStringExtra(GeofenceUtils.EXTRA_GEOFENCE_STATUS);
            Log.e(GeofenceUtils.APPTAG, msg);
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
        }

    }

    // ==========================================================================
}
