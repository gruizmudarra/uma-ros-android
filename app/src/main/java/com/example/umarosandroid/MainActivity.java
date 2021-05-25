package com.example.umarosandroid;

import android.Manifest;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.ros.address.InetAddressFactory;
import org.ros.android.MasterChooser;
import org.ros.android.NodeMainExecutorService;
import org.ros.exception.RosRuntimeException;
import org.ros.node.NodeConfiguration;
import org.ros.node.NodeMainExecutor;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;


public class MainActivity extends AppCompatActivity {
    private static final int MASTER_CHOOSER_REQUEST_CODE = 0;

    private ServiceConnection nodeMainExecutorServiceConnection;
    private NodeMainExecutorService nodeMainExecutorService;

    // Camera requests
    private static final String[] CAMERA_PERMISSION = new String[]{Manifest.permission.CAMERA};
    private static final int CAMERA_REQUEST_CODE = 10;

    // Coarse location requests
    private static final String[] COARSE_LOCATION_PERMISSION = new String[]{Manifest.permission.ACCESS_COARSE_LOCATION};
    private static final int COARSE_LOCATION_REQUEST_CODE = 10;

    // Fine location requests
    private static final String[] FINE_LOCATION_PERMISSION = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    private static final int FINE_LOCATION_REQUEST_CODE = 10;

    // Camera requests
    private static final String[] AUDIO_PERMISSION = new String[]{Manifest.permission.RECORD_AUDIO};
    private static final int AUDIO_REQUEST_CODE = 10;

    //private FusedLocationProviderClient fusedLocationClient;
    private LocationManager mLocationManager;

    // IMU and Camera instances and views
    private SensorManager sensorManager;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private PreviewView previewView;

    private AudioManager mAudioManager;

    private String nodeName = "android0";

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        previewView = findViewById(R.id.previewView);

        if(!hasCameraPermission()) {
            requestCameraPermission();
        }
        if(!hasFineLocationPermission()) {
            requestFineLocationPermission();
        }
        /*
        if(!hasAudioPermission()) {
            requestAudioPermission();
        }
        */
        nodeMainExecutorServiceConnection = new NodeMainExecutorServiceConnection(null);
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        mLocationManager = (LocationManager)this.getSystemService(LOCATION_SERVICE);

        final boolean gpsEnabled = mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        if (!gpsEnabled) {
            // Build an alert dialog here that requests that the user enable
            // the location services, then when the user clicks the "OK" button,
            // call enableLocationSettings()
            enableLocationSettings();
        }

        /*
        mAudioManager = (AudioManager)this.getSystemService(AUDIO_SERVICE);
        String x = mAudioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED);
        */
    }

    @Override
    protected void onStart() {
        super.onStart();
        final Intent intent = new Intent(this, NodeMainExecutorService.class);
        intent.setAction(NodeMainExecutorService.ACTION_START);
        intent.putExtra(NodeMainExecutorService.EXTRA_NOTIFICATION_TICKER, getString(R.string.app_name));
        intent.putExtra(NodeMainExecutorService.EXTRA_NOTIFICATION_TITLE, getString(R.string.app_name));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        if (!bindService(intent, nodeMainExecutorServiceConnection, BIND_AUTO_CREATE)) {
            Toast.makeText(this, "Failed to bind NodeMainExecutorService.", Toast.LENGTH_LONG).show();
        }




    }

    private void enableLocationSettings() {
        Intent settingsIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        startActivity(settingsIntent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(nodeMainExecutorServiceConnection);
        final Intent intent = new Intent(this, NodeMainExecutorService.class);
        stopService(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            if (requestCode == MASTER_CHOOSER_REQUEST_CODE) {
                final String host;
                final String networkInterfaceName = data.getStringExtra("ROS_MASTER_NETWORK_INTERFACE");
                // Handles the default selection and prevents possible errors
                if (TextUtils.isEmpty(networkInterfaceName)) {
                    host = InetAddressFactory.newNonLoopback().getHostAddress();
                } else {
                    try {
                        final NetworkInterface networkInterface = NetworkInterface.getByName(networkInterfaceName);
                        host = InetAddressFactory.newNonLoopbackForNetworkInterface(networkInterface).getHostAddress();
                    } catch (final SocketException e) {
                        throw new RosRuntimeException(e);
                    }
                }
                nodeMainExecutorService.setRosHostname(host);
                if (data.getBooleanExtra("ROS_MASTER_CREATE_NEW", false)) {
                    nodeMainExecutorService.startMaster(data.getBooleanExtra("ROS_MASTER_PRIVATE", true));
                } else {
                    final URI uri;
                    try {
                        uri = new URI(data.getStringExtra("ROS_MASTER_URI"));
                    } catch (final URISyntaxException e) {
                        throw new RosRuntimeException(e);
                    }
                    nodeMainExecutorService.setMasterUri(uri);
                }
                // Run init() in a new thread as a convenience since it often requires network access.
                new Thread(() -> init(nodeMainExecutorService)).start();
            } else {
                // Without a master URI configured, we are in an unusable state.
                nodeMainExecutorService.forceShutdown();
            }
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED;
    }

    // Requests camera permission
    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(
                this,
                CAMERA_PERMISSION,
                CAMERA_REQUEST_CODE
        );
    }

    private boolean hasCoarseLocationPermission() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
        )== PackageManager.PERMISSION_GRANTED;
    }

    private void requestCoarseLocationPermission() {
        ActivityCompat.requestPermissions(
                this,
                COARSE_LOCATION_PERMISSION,
                COARSE_LOCATION_REQUEST_CODE
        );
    }

    private boolean hasFineLocationPermission() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        )== PackageManager.PERMISSION_GRANTED;
    }

    private void requestFineLocationPermission() {
        ActivityCompat.requestPermissions(
                this,
                FINE_LOCATION_PERMISSION,
                FINE_LOCATION_REQUEST_CODE
        );
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED;
    }

    // Requests camera permission
    private void requestAudioPermission() {
        ActivityCompat.requestPermissions(
                this,
                AUDIO_PERMISSION,
                AUDIO_REQUEST_CODE
        );
    }

    protected void init(NodeMainExecutor nodeMainExecutor) {
        ImuNode imuNode = new ImuNode(sensorManager,nodeName);
        CameraNode cameraNode = new CameraNode(this,cameraProviderFuture,previewView,nodeName);
        GPSNode gpsNode = new GPSNode(this,mLocationManager,nodeName);
        //AudioNode audioNode = new AudioNode(this,nodeName,mAudioManager);

        //Network configuration with ROS master
        final NodeConfiguration nodeConfiguration = NodeConfiguration.newPublic(
                InetAddressFactory.newNonLoopback().getHostAddress()
        );
        nodeConfiguration.setMasterUri(nodeMainExecutorService.getMasterUri());

        // Run nodes
        nodeMainExecutor.execute(imuNode, nodeConfiguration);
        nodeMainExecutor.execute(cameraNode, nodeConfiguration);
        nodeMainExecutor.execute(gpsNode,nodeConfiguration);
        //nodeMainExecutor.execute(audioNode,nodeConfiguration);
    }
    
    @SuppressWarnings("NonStaticInnerClassInSecureContext")
    private final class NodeMainExecutorServiceConnection implements ServiceConnection {

        private final URI customMasterUri;

        public NodeMainExecutorServiceConnection(final URI customUri) {
            customMasterUri = customUri;
        }

        @Override
        public void onServiceConnected(final ComponentName name, final IBinder binder) {
            nodeMainExecutorService = ((NodeMainExecutorService.LocalBinder) binder).getService();

            if (customMasterUri != null) {
                nodeMainExecutorService.setMasterUri(customMasterUri);
                final String host = InetAddressFactory.newNonLoopback().getHostAddress();
                nodeMainExecutorService.setRosHostname(host);
            }
            nodeMainExecutorService.addListener(executorService -> {
                // We may have added multiple shutdown listeners and we only want to
                // call finish() once.
                if (!isFinishing()) {
                    finish();
                }
            });
            if (nodeMainExecutorService.getMasterUri() == null) {
                startActivityForResult(
                        new Intent(MainActivity.this, MasterChooser.class),
                        MASTER_CHOOSER_REQUEST_CODE
                );
            } else {
                init(nodeMainExecutorService);
            }
        }

        @Override
        public void onServiceDisconnected(final ComponentName name) {
            Toast.makeText(MainActivity.this, "Service disconnected", Toast.LENGTH_LONG).show();
        }
    }
}