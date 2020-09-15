package com.example.rabbitmqexample;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.StrictMode;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

public class MainActivity extends Activity {

    public static final String LOG_TAG = "myLogs";
    CameraService[] myCameras = null;
    private CameraManager mCameraManager = null;
    private TextureView mImageView = null;
    private MediaRecorder mMediaRecorder = null;
    private File mCurrentFile;
    private int count =1;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler = null;

    private MediaCodec mCodec = null; // кодер
    Surface mEncoderSurface; // Surface как вход данных для кодера
    BufferedOutputStream outputStream;
    ByteBuffer outPutByteBuffer;
    DatagramSocket udpSocket;
    String ip_address = "192.168.220.87";
    InetAddress address;
    int port = 40002;

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
        setContentView(R.layout.activity_main);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        try {
            setupConnectionFactory();
        } catch (Exception ex){
            java.lang.System.out.println(ex.getLocalizedMessage());
        }

        publishToAMQP();
        setupPubButton();
        recordButton();

        final Handler incomingMessageHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                String message = msg.getData().getString("msg");
                TextView tv = (TextView) findViewById(R.id.textView);
                Date now = new Date();
                SimpleDateFormat ft = new SimpleDateFormat("hh:mm:ss");
                tv.append(ft.format(now) + ' ' + message + '\n');
            }
        };
        subscribe(incomingMessageHandler);
    }

    void subscribe(final Handler handler) {
        subscribeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(true) {
                    try {
                        Connection connection = factory.newConnection();
                        Channel channel = connection.createChannel();
                        channel.basicQos(1);
                        AMQP.Queue.DeclareOk q = channel.queueDeclare();
                        channel.queueBind(q.getQueue(), "amq.fanout", "chat");
                        DefaultConsumer consumer = new DefaultConsumer(channel);
                        String[] message = {""};
                        DefaultConsumer customConsumer =  new DefaultConsumer(channel) {
                            @Override
                            public void handleDelivery(String consumerTag,
                                                       Envelope envelope,
                                                       AMQP.BasicProperties properties,
                                                       byte[] body)  throws IOException {
                                String routingKey = envelope.getRoutingKey();
                                String contentType = properties.getContentType();
                                long deliveryTag = envelope.getDeliveryTag();
                                // (process the message components here ...)
                                message[0] = new String(body, "UTF-8");
//                                java.lang.System.out.println(message[0]);
                                channel.basicAck(deliveryTag, false);
                            }
                        };
                        channel.basicConsume(q.getQueue(), true, customConsumer);

                        while (true) {
//                            QueueingConsumer.Delivery delivery = consumer.nextDelivery();
//                            String message = new String(delivery.getBody());
//                            java.lang.System.out.println( customConsumer.getConsumerTag() );
//                            java.lang.System.out.println(customConsumer.getChannel());
//                            String message = "some message here";
                            // TODO get message from DefaultConsumer
                            if(!message[0].equals("")){
                                Log.d("","[r] " + message[0]);
                                Message msg = handler.obtainMessage();
                                Bundle bundle = new Bundle();
                                bundle.putString("msg", message[0]);
                                msg.setData(bundle);
                                handler.sendMessage(msg);
                                message[0] = "";
                            }

                        }
//                    } catch (InterruptedException e) {
//                        break;
                    } catch (Exception e1) {
                        Log.d("", "Connection broken: " + e1.getClass().getName());
                        try {
                            Thread.sleep(5000); //sleep and then try again
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
            }
        });
        subscribeThread.start();
    }

    void setupPubButton() {
        Button button = (Button) findViewById(R.id.publish);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View arg0) {
                EditText et = (EditText) findViewById(R.id.text);
                publishMessage(et.getText().toString());
                et.setText("");
            }
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    void recordButton() {
        Button button = (Button) findViewById(R.id.record);
//        mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View arg0) {
                mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                java.lang.System.out.println("Запущена камера");
                try{
                    myCameras = new CameraService[mCameraManager.getCameraIdList().length];
                    // выводим информацию по камере
                    for (String cameraID : mCameraManager.getCameraIdList()) {
//                        Log.i(LOG_TAG, "cameraID: "+cameraID);
//                        int id = Integer.parseInt(cameraID);
//
//                        // Получениe характеристик камеры
//                        CameraCharacteristics cc = mCameraManager.getCameraCharacteristics(cameraID);
//                        // Получения списка выходного формата, который поддерживает камера
//                        StreamConfigurationMap configurationMap =
//                                cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
//
//                        //  Определение какая камера куда смотрит
//                        int Faceing = cc.get(CameraCharacteristics.LENS_FACING);
//
//                        if (Faceing ==  CameraCharacteristics.LENS_FACING_FRONT) {
//                            Log.i(LOG_TAG,"Camera with ID: "+cameraID +  "  is FRONT CAMERA  ");
//                        }
//
//                        if (Faceing ==  CameraCharacteristics.LENS_FACING_BACK) {
//                            Log.i(LOG_TAG,"Camera with: ID "+cameraID +  " is BACK CAMERA  ");
//                        }
                        Log.i(LOG_TAG, "cameraID: "+cameraID);
                        int id = Integer.parseInt(cameraID);
                        // создаем обработчик для камеры
                        myCameras[id] = new CameraService(mCameraManager,cameraID);
                        String debug = "";
                        if(id == 0){
                            try {
                                udpSocket = new DatagramSocket();

                                Log.i(LOG_TAG, "  создали udp сокет");

                            } catch (
                                    SocketException e) {
                                Log.i(LOG_TAG, " не создали udp сокет");
                            }

                            try {
                                address = InetAddress.getByName(ip_address);
                                Log.i(LOG_TAG, "  есть адрес");
                            } catch (Exception e) {
                                Log.i(LOG_TAG, " ошибка" + e.getLocalizedMessage());
                            }

                            myCameras[0].openCamera();
                            setUpMediaRecorder();
                            setUpMediaCodec();
                            mMediaRecorder.start();
                            debug = "123";
                        }
//                        if (myCameras[0].isOpen()) {myCameras[1].closeCamera();}
//                        if (myCameras[0] != null) {
//                            if (!myCameras[0].isOpen()) myCameras[0].openCamera();
//                        }
                    }
                }
                catch(CameraAccessException ex){
                    Log.e(LOG_TAG, ex.getLocalizedMessage());
                    ex.printStackTrace();
                }

            }
        });
    }

    Thread subscribeThread;
    Thread publishThread;
    @Override
    protected void onDestroy() {
        super.onDestroy();
        publishThread.interrupt();
        subscribeThread.interrupt();
    }

    private BlockingDeque<String> queue = new LinkedBlockingDeque <String>();
    void publishMessage(String message) {
        try {
            Log.d("","[q] " + message);
            queue.putLast(message);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    ConnectionFactory factory = new ConnectionFactory();
    private void setupConnectionFactory() {
//        String uri = "CLOUDAMQP_URL";
        String uri = "amqp://test:test@192.168.220.87:5672";
        try {
            factory.setAutomaticRecoveryEnabled(false);
            factory.setUri(uri);
        } catch (KeyManagementException | NoSuchAlgorithmException | URISyntaxException e1) {
            e1.printStackTrace();
        }
    }

    public void publishToAMQP() {
        publishThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(true) {
                    try {
                        Connection connection = factory.newConnection();
                        Channel ch = connection.createChannel();
                        ch.confirmSelect();

                        while (true) {
                            String message = queue.takeFirst();
                            try{
                                ch.basicPublish("amq.fanout", "chat", null, message.getBytes());
                                Log.d("", "[s] " + message);
                                ch.waitForConfirmsOrDie();
                            } catch (Exception e){
                                Log.d("","[f] " + message);
                                queue.putFirst(message);
                                throw e;
                            }
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        Log.d("", "Connection broken: " + e.getClass().getName());
                        try {
                            Thread.sleep(5000); //sleep and then try again
                        } catch (InterruptedException e1) {
                            break;
                        }
                    }
                }
            }
        });
        publishThread.start();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public class CameraService {

        private String mCameraID;
        private CameraDevice mCameraDevice = null;
        private CameraCaptureSession mCaptureSession;
        private CaptureRequest.Builder mPreviewBuilder;

        public CameraService(CameraManager cameraManager, String cameraID) {

            mCameraManager = cameraManager;
            mCameraID = cameraID;

        }

        private CameraDevice.StateCallback mCameraCallback = new CameraDevice.StateCallback() {

            @Override
            public void onOpened(CameraDevice camera) {
                mCameraDevice = camera;
                Log.i(LOG_TAG, "Open camera  with id:"+mCameraDevice.getId());

                createCameraPreviewSession();
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                mCameraDevice.close();

                Log.i(LOG_TAG, "disconnect camera  with id:"+mCameraDevice.getId());
                mCameraDevice = null;
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                Log.i(LOG_TAG, "error! camera id:"+camera.getId()+" error:"+error);
            }
        };

        private ImageReader mImageReader;

        private void createCameraPreviewSession() {

            mImageReader = ImageReader.newInstance(1920,1080, ImageFormat.JPEG,1);
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, null);

            mImageView = findViewById(R.id.textureView);
            SurfaceTexture texture = mImageView.getSurfaceTexture();

//            texture.setDefaultBufferSize(1920,1080);
            assert texture != null;
            texture.setDefaultBufferSize(640, 480);
            Surface surface = new Surface(texture);

            try {
//                final CaptureRequest.Builder builder =
//                        mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
//
//                builder.addTarget(surface);

//                mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),
//                        new CameraCaptureSession.StateCallback() {
//
//                            @Override
//                            public void onConfigured(CameraCaptureSession session) {
//                                mCaptureSession = session;
//                                try {
//                                    mCaptureSession.setRepeatingRequest(builder.build(),null,null);
//                                } catch (CameraAccessException e) {
//                                    e.printStackTrace();
//                                }
//                            }
//
//                            @Override
//                            public void onConfigureFailed(CameraCaptureSession session) { }}, null );
                mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

                /**Surface for the camera preview set up*/

                mPreviewBuilder.addTarget(surface);

                /**MediaRecorder setup for surface*/

                Surface recorderSurface = mMediaRecorder.getSurface();

                mPreviewBuilder.addTarget(recorderSurface);

                mCameraDevice.createCaptureSession(Arrays.asList(surface, mMediaRecorder.getSurface()),
                        new CameraCaptureSession.StateCallback() {

                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                mCaptureSession = session;

                                try {
                                    mCaptureSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
                                } catch (CameraAccessException e) {
                                    e.printStackTrace();
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                            }
                        }, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

        }

        public boolean isOpen() {
            return mCameraDevice != null;
        }

        public void openCamera() {
            try {
                String debug = "";
                int check = checkSelfPermission(Manifest.permission.CAMERA);
                int perm = PackageManager.PERMISSION_GRANTED;
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    mCameraManager.openCamera(mCameraID,mCameraCallback,null);
                    debug = "12";
                }

            } catch (CameraAccessException e) {
                Log.i(LOG_TAG, Objects.requireNonNull(e.getMessage()));

            }
        }

        public void closeCamera() {
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        }
    }

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {

            { Toast.makeText(MainActivity.this,"фотка доступна для сохранения", Toast.LENGTH_SHORT).show();}
        }
    };

    private void setUpMediaRecorder() {

        mMediaRecorder = new MediaRecorder();

        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mCurrentFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "test"+count+".mp4");
        mMediaRecorder.setOutputFile(mCurrentFile.getAbsolutePath());
        CamcorderProfile profile = CamcorderProfile.get(CamcorderProfile.QUALITY_LOW);
        mMediaRecorder.setVideoFrameRate(profile.videoFrameRate);
        mMediaRecorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
        mMediaRecorder.setVideoEncodingBitRate(profile.videoBitRate);
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mMediaRecorder.setAudioEncodingBitRate(profile.audioBitRate);
        mMediaRecorder.setAudioSamplingRate(profile.audioSampleRate);

        try {
            mMediaRecorder.prepare();
            Log.i(LOG_TAG, " запустили медиа рекордер");

        } catch (Exception e) {
            Log.i(LOG_TAG, "не запустили медиа рекордер");
        }


    }

    private void setUpMediaCodec() {

        try {
            mCodec = MediaCodec.createEncoderByType("video/avc"); // H264 кодек

        } catch (Exception e) {
            Log.i(LOG_TAG, "нет кодека");
        }

        int width = 320; // ширина видео
        int height = 240; // высота видео
        int colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface; // формат ввода цвета
        int videoBitrate = 500000; // битрейт видео в bps (бит в секунду)
        int videoFramePerSecond = 20; // FPS
        int iframeInterval = 3; // I-Frame интервал в секундах

        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, videoFramePerSecond);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframeInterval);


        mCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); // конфигурируем кодек как кодер
        mEncoderSurface = mCodec.createInputSurface(); // получаем Surface кодера
        EncoderCallback encoderCallback = new EncoderCallback();
        mCodec.setCallback(encoderCallback);
        mCodec.start(); // запускаем кодер
        Log.i(LOG_TAG, "запустили кодек");

    }

    private class EncoderCallback extends MediaCodec.Callback {

        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {

        }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {


            outPutByteBuffer = mCodec.getOutputBuffer(index);
            byte[] outDate = new byte[info.size];
            outPutByteBuffer.get(outDate);

            try {
                DatagramPacket packet = new DatagramPacket(outDate, outDate.length, address, port);
                udpSocket.send(packet);
            } catch (IOException e) {
                Log.i(LOG_TAG, " не отправился UDP пакет");
            }


            mCodec.releaseOutputBuffer(index, false);


        }

        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            Log.i(LOG_TAG, "Error: " + e);
        }

        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            Log.i(LOG_TAG, "encoder output format changed: " + format);
        }
    }

//    @Override
//    public void onPause() {
//        if(myCameras[CAMERA1].isOpen()){myCameras[CAMERA1].closeCamera();}
//        if(myCameras[CAMERA2].isOpen()){myCameras[CAMERA2].closeCamera();}
//        super.onPause();
//    }

}