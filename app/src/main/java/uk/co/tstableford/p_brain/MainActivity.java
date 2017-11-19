package uk.co.tstableford.p_brain;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.speech.tts.UtteranceProgressListener;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Toast;
import android.speech.tts.TextToSpeech;
import android.os.Build;
import android.speech.RecognizerIntent;

import com.github.nkzawa.emitter.Emitter;
import com.github.nkzawa.socketio.client.IO;
import com.github.nkzawa.socketio.client.Socket;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

import uk.co.tstableford.p_brain.service.LocalService;

public class MainActivity extends Activity {
    private static final String TAG = "PBrainMain";
    private static final int REQ_CREATE_TRAINING_DATA = 101;
    public static final int PERMISSION_RESULT = 102;
    private static final int SPEECH_TIMEOUT = 20000; // 20 seconds in milliseconds.
    private SharedPreferences preferences;
    private EditText chatEditText1;
    private ArrayList<ChatMessage> chatMessages;
    private ImageView enterChatView1;
    private ChatListAdapter listAdapter;
    private Socket mSocket;
    private HotwordDetector hotwordDetector;
    private TextToSpeech tts;
    private String name;
    private SpeechRecognizer speechRecognizer;
    private String connectedServer = null;
    private ConnectionManager.AuthListener validationListener;
    private ConnectionManager.AuthListener loginListener;
    private boolean promptForResponseOnSpeechEnd = false;
    private ListeningDialog listeningDialog;
    private SpeakingDialog speakingDialog;
    private SpeechListener speechListener;

    @Override
    public void onDestroy() {
        if (hotwordDetector != null) {
            hotwordDetector.destroy();
        }
        teardownSocket();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        Intent intent = new Intent(this, LocalService.class);
        stopService(intent);
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (hotwordDetector != null) {
            hotwordDetector.setDisabled(false);
        }

        try {
            if (!requestPermissions()) {
                Log.e(TAG, "Not got all permissions.");
                return;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to find this package.", e);
            return;
        }

        final String server = preferences.getString("server_address", null);
        if (server == null) {
            Toast.makeText(this, "Enter server address.", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        final String token = preferences.getString("token", null);
        final ConnectionManager manager = new ConnectionManager(this, server);

        loginListener = new ConnectionManager.AuthListener() {
            @Override
            public void onSuccess(String token) {
                preferences.edit().putString("token", token).apply();
                finishSetup(token);
            }

            @Override
            public void onFailure(String reason, int status) {
                // Should never reach this point. Handled in login dialog.
            }
        };

        validationListener = new ConnectionManager.AuthListener() {
            @Override
            public void onSuccess(String token) {
                finishSetup(token);
            }

            @Override
            public void onFailure(String reason, int status) {
                switch (status) {
                    case 403: // Fallthrough.
                    case 401:
                        // Show login prompt.
                        new LoginDialog(MainActivity.this, server, loginListener).show();
                        Toast.makeText(MainActivity.this, reason, Toast.LENGTH_LONG).show();
                        break;
                    default:
                        new RetryDialog(MainActivity.this, new RetryDialog.Response() {
                            @Override
                            public void onRetry() {
                                manager.validateToken(token, validationListener);
                            }

                            @Override
                            public void onCancel() {
                                Toast.makeText(MainActivity.this, "Please restart the app or update connection settings.", Toast.LENGTH_LONG).show();
                            }
                        }).show();
                        Toast.makeText(MainActivity.this, reason, Toast.LENGTH_LONG).show();
                        break;
                }
            }
        };

        manager.validateToken(token, validationListener);
    }

    private void finishSetup(String token) {
        final String server = preferences.getString("server_address", null);
        setupSocket(server, token);

        boolean hotwordEnabled = preferences.getBoolean("hotword_enabled", true);
        if (hotwordEnabled && hotwordDetector == null) {
            try {
                hotwordDetector = new HotwordDetector(this);
                if (name != null) {
                    hotwordDetector.setKeyword(name);
                }
                hotwordDetector.setHotwordListener(new HotwordListener() {
                    @Override
                    public void onHotword() {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                promptSpeechInput();
                            }
                        });
                    }
                });
            } catch (IOException e) {
                Log.e(TAG, "Failed to initialise hotword detector.", e);
            }
        } else if (!hotwordEnabled && hotwordDetector != null) {
            hotwordDetector.destroy();
            hotwordDetector = null;
        }

        if (hotwordDetector != null) {
            hotwordDetector.startListening();
        }
    }

    @Override
    public void onPause() {
        speechListener.cancel();
        if (hotwordDetector != null) {
            hotwordDetector.setDisabled(true);
            hotwordDetector.stopListening();
        }
        super.onPause();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent(this, LocalService.class);
        startService(intent);
        setContentView(R.layout.activity_main);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        listeningDialog = new ListeningDialog(this);
        speakingDialog = new SpeakingDialog(this);
        speakingDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                tts.stop();
                if (promptForResponseOnSpeechEnd) {
                    promptForResponseOnSpeechEnd = false;
                    boolean hotwordEnabled = preferences.getBoolean("hotword_enabled", true);
                    if (hotwordEnabled) {
                        promptSpeechInput();
                    }
                } else {
                    if (hotwordDetector != null) {
                        hotwordDetector.startListening();
                    }
                }
            }
        });

        chatEditText1 = (EditText) findViewById(R.id.chat_edit_text1);
        enterChatView1 = (ImageView) findViewById(R.id.enter_chat1);

        chatMessages = new ArrayList<>();
        ListView chatListView = (ListView) findViewById(R.id.chat_list_view);
        listAdapter = new ChatListAdapter(chatMessages, this);
        chatListView.setAdapter(listAdapter);

        chatEditText1.setOnKeyListener(keyListener);

        enterChatView1.setOnClickListener(clickListener);

        chatEditText1.addTextChangedListener(watcher1);

        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    int result = tts.setLanguage(Locale.getDefault());
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e("TTS", "This Language is not supported");
                    }
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String utteranceId) {}

                        @Override
                        public void onDone(String utteranceId) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    speakingDialog.dismiss();
                                }
                            });
                        }

                        @Override
                        public void onError(String utteranceId) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "Speech error", Toast.LENGTH_LONG).show();
                                    if (hotwordDetector != null) {
                                        hotwordDetector.startListening();
                                    }
                                    speakingDialog.dismiss();
                                }
                            });
                        }
                    });
                } else {
                    Log.e("TTS", "Initilization Failed!");
                }
            }
        });

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechListener = new SpeechListener();
        speechRecognizer.setRecognitionListener(speechListener);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.settings_item: {
                Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
                return true;
            }
            case R.id.train_item: {
                if (name != null) {
                    Intent intent = new Intent(this, TrainActivity.class);
                    intent.putExtra(TrainActivity.NAME_INTENT, name);
                    startActivityForResult(intent, REQ_CREATE_TRAINING_DATA);
                } else {
                    Toast.makeText(this, R.string.cant_train_without_name, Toast.LENGTH_LONG).show();
                }
                return true;
            }
            case R.id.change_user: {
                final String server = preferences.getString("server_address", null);
                LoginDialog login = new LoginDialog(MainActivity.this, server, new ConnectionManager.AuthListener() {
                    @Override
                    public void onSuccess(String token) {
                        preferences.edit().putString("token", token).apply();
                        finishSetup(token);
                    }

                    @Override
                    public void onFailure(String reason, int status) {
                        // Should never reach this point. Handled in login dialog.
                    }
                });
                login.setCancelable(true);
                login.setCanceledOnTouchOutside(true);
                login.show();
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.settings_menu, menu);
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case REQ_CREATE_TRAINING_DATA: {
                if (resultCode == RESULT_OK) {
                    if (hotwordDetector != null) {
                        if (hotwordDetector.setKeyword(name)) {
                            statusMessage(getString(R.string.keyword_update_success, name));
                            hotwordDetector.startListening();
                        } else {
                            statusMessage(getString(R.string.keyword_update_failure, name));
                        }
                    }
                }
            }

        }
    }

    @SuppressWarnings("deprecation")
    private void speak(String text) {
        boolean ttsEnabled = preferences.getBoolean("tts_enabled", true);
        if (ttsEnabled) {
            speakingDialog.show();
            // Stop listening so it doesn't trigger itself.
            if (hotwordDetector != null) {
                hotwordDetector.stopListening();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "messageID");
            } else {
                HashMap<String, String> map = new HashMap<String, String>();
                map.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,"messageID");
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, map);
            }
        }
    }

    /**
     * Showing google speech input dialog
     */
    private void promptSpeechInput() {
        if (hotwordDetector != null) {
            hotwordDetector.stopListening();
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, "uk.co.tstableford.p_brain");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra("android.speech.extra.DICTATION_MODE", true);

        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        speechRecognizer.startListening(intent);
        listeningDialog.show();
    }

    private void teardownSocket() {
        if (mSocket != null) {
            mSocket.disconnect();
            mSocket.close();
            mSocket.off();
            mSocket = null;
        }
    }

    private void setupSocket(String server, String token) {
        if (server == null) {
            return;
        }
        if (!server.equals(connectedServer) || mSocket == null) {
            teardownSocket();
            try {
                IO.Options opts = new IO.Options();
                opts.forceNew = true;
                opts.query = "token=" + token;
                mSocket = IO.socket(server, opts);
                setupSocketListeners();
                connectedServer = server;
                mSocket.connect();
            } catch (URISyntaxException e) {
                Log.e(TAG, "Error connecting socket.io.", e);
                statusMessage(getString(R.string.server_not_connected));
            }
        }
    }

    private void setupSocketListeners() {
        mSocket.on("response", new Emitter.Listener() {
            @Override
            public void call(final Object... args) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        JSONObject data = (JSONObject) args[0];
                        try {
                            JSONObject msgObject = data.getJSONObject("msg");
                            // Response will be what is spoken.
                            String response = msgObject.getString("text");
                            // Text will include the response and additional details such as URL.
                            String text = response;

                            if (msgObject.has("url")) {
                                String url = msgObject.getString("url");
                                text = text + " " + url;
                                if (msgObject.has("url_autolaunch")) {
                                    if (msgObject.getBoolean("url_autolaunch")) {
                                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                                    }
                                }
                            }

                            boolean silent = false;
                            if (msgObject.has("silent")) {
                                if (msgObject.getBoolean("silent")) {
                                    silent = true;
                                }
                            }
                            boolean canRespond = false;
                            if (msgObject.has("canRespond")) {
                                if (msgObject.getBoolean("canRespond")) {
                                    canRespond = true;
                                }
                            }
                            if (!silent && canRespond) {
                                promptForResponseOnSpeechEnd = true;
                            }

                            responseMessage(response, text, silent);
                        } catch (JSONException e) {
                            Log.e(TAG, "Error decoding JSON packet.", e);
                        }
                    }
                });
            }
        });

        mSocket.on("set_name", new Emitter.Listener() {
            @Override
            public void call(final Object... args) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        JSONObject data = (JSONObject) args[0];
                        try {
                            name = data.getString("name");
                            if (hotwordDetector != null) {
                                if (hotwordDetector.setKeyword(name)) {
                                    Log.i(TAG, "Keyword set to " + name);
                                    statusMessage(getString(R.string.voice_prompt, name));
                                } else {
                                    hotwordDetector.stopListening();
                                    speechListener.cancel();
                                    Toast.makeText(MainActivity.this, getString(R.string.missing_training_data, name), Toast.LENGTH_LONG).show();
                                    Intent intent = new Intent(MainActivity.this, TrainActivity.class);
                                    intent.putExtra(TrainActivity.NAME_INTENT, name);
                                    startActivityForResult(intent, REQ_CREATE_TRAINING_DATA);
                                }
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "Error decoding JSON packet.", e);
                        }
                    }
                });
            }
        });

        mSocket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
            @Override
            public void call(final Object... args) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        statusMessage(getString(R.string.connected));
                    }
                });
            }
        });

        mSocket.on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
            @Override
            public void call(final Object... args) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        statusMessage(getString(R.string.disconnected));
                    }
                });
            }
        });
    }

    private boolean requestPermissions() throws PackageManager.NameNotFoundException {
        PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
        ArrayList<String> toRequest = new ArrayList<>();
        if (info.requestedPermissions != null) {
            for (String p : info.requestedPermissions) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    toRequest.add(p);
                }
            }
        }

        if (toRequest.size() > 0) {
            String[] tra = new String[toRequest.size()];
            for (int i = 0; i < tra.length; i++) {
                tra[i] = toRequest.get(i);
            }
            ActivityCompat.requestPermissions(this, tra, PERMISSION_RESULT);
        }
        return toRequest.size() == 0;
    }

    private class SpeechTimeout implements Runnable {
        private boolean timeoutCancelled = false;
        @Override
        public void run() {
            if (!timeoutCancelled) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        speechRecognizer.stopListening();
                        listeningDialog.dismiss();
                        if (hotwordDetector != null) {
                            hotwordDetector.startListening();
                        }
                    }
                });
            }
        }

        void cancel() {
            timeoutCancelled = true;
        }
    }

    private class SpeechListener implements RecognitionListener {
        private SpeechTimeout timeout = null;
        public void onReadyForSpeech(Bundle params) {
            if (timeout != null) {
                timeout.cancel();
            }
            timeout = new SpeechTimeout();
            new android.os.Handler().postDelayed(timeout, SPEECH_TIMEOUT);
        }

        void cancel() {
            if (timeout != null) {
                timeout.cancel();
                speechRecognizer.stopListening();
                listeningDialog.dismiss();
                timeout = null;
            }
        }

        public void onBeginningOfSpeech() { }

        public void onRmsChanged(float rmsdB) { }

        public void onBufferReceived(byte[] buffer) { }

        public void onEndOfSpeech() { }

        public void onError(int error) {
            listeningDialog.dismiss();
            Log.i(TAG, "Speech recognition error " + error);
            if (hotwordDetector != null) {
                hotwordDetector.startListening();
            }
            if (timeout != null) {
                timeout.cancel();
                timeout = null;
            }
        }

        public void onResults(Bundle results) {
            Log.i(TAG, "onResults " + results);
            if (timeout != null) {
                timeout.cancel();
                timeout = null;
                listeningDialog.dismiss();
                if (hotwordDetector != null) {
                    hotwordDetector.startListening();
                }
                ArrayList data = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (data != null && data.size() > 0) {
                    sendMessage((String) data.get(0));
                }
            }
        }

        public void onPartialResults(Bundle results) { }

        public void onEvent(int eventType, Bundle params) { }
    }

    private boolean addMessage(String text, ChatMessage.UserType type) {
        if (text.trim().length() == 0) {
            return false;
        }
        final ChatMessage message = new ChatMessage();
        message.setMessageText(text);
        message.setUserType(type);
        chatMessages.add(message);

        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        return true;
    }

    private void sendMessage(final String messageText) {
        if (addMessage(messageText, ChatMessage.UserType.SELF)) {
            JSONObject object = new JSONObject();
            if (mSocket == null) {
                statusMessage(getString(R.string.server_not_connected));
            } else {
                try {
                    object.put("text", messageText);
                    mSocket.emit("ask", object);
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to add message to JSON.", e);
                }
            }
        }
    }

    private void responseMessage(String spokenText, String writtenText, boolean silent) {
        if (addMessage(writtenText, ChatMessage.UserType.OTHER)) {
            if (!silent) {
                speak(spokenText);
            }
        }
    }

    private void statusMessage(final String messageText) {
        addMessage(messageText, ChatMessage.UserType.STATUS);
    }

    private EditText.OnKeyListener keyListener = new View.OnKeyListener() {
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            // If the event is a key-down event on the "enter" button
            if ((event.getAction() == KeyEvent.ACTION_DOWN) &&
                    (keyCode == KeyEvent.KEYCODE_ENTER)) {
                // Perform action on key press
                EditText editText = (EditText) v;
                if (v == chatEditText1) {
                    sendMessage(editText.getText().toString());
                }
                chatEditText1.setText("");
                return true;
            }
            return false;
        }
    };

    private ImageView.OnClickListener clickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (v == enterChatView1) {
                sendMessage(chatEditText1.getText().toString());
            }
            chatEditText1.setText("");
        }
    };

    private final TextWatcher watcher1 = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i2, int i3) {
        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i2, int i3) {
        }

        @Override
        public void afterTextChanged(Editable editable) {
            if (editable.length() == 0) {
                enterChatView1.setImageResource(R.drawable.ic_chat_send);
            } else {
                enterChatView1.setImageResource(R.drawable.ic_chat_send_active);
            }
        }
    };
}
