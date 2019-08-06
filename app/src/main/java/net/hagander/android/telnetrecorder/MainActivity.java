package net.hagander.android.telnetrecorder;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;

import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {
    public TextView txtContents;
    public TextView txtBytesTotal;
    public Button btnConnect;
    private Long totalBytes = 0L;
    private String dataSample = "";
    private String displayedDataSample;

    private Socket sock = null;
    File recordingFile = null;
    FileOutputStream recordingStream = null;
    boolean isDisconnecting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnConnect = findViewById(R.id.btnConnectDisconnect);
        txtContents = findViewById(R.id.txtContent);
        txtBytesTotal = findViewById(R.id.txtBytesTotal);

        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (sock!= null) {
                    Toast.makeText(MainActivity.this, "Disconnecting...", Toast.LENGTH_SHORT).show();
                    isDisconnecting = true;
                    try {
                        sock.close();
                    } catch (IOException e) {
                    }
                }
                else {
                    final SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                    LinearLayout layout = new LinearLayout(MainActivity.this);
                    layout.setOrientation(LinearLayout.VERTICAL);
                    final EditText hostBox = new EditText(MainActivity.this);
                    hostBox.setHint("Host");
                    hostBox.setText(sharedPref.getString("host", ""));
                    layout.addView(hostBox);

                    final EditText portBox = new EditText(MainActivity.this);
                    portBox.setHint("Port");
                    portBox.setInputType(InputType.TYPE_CLASS_NUMBER);
                    portBox.setText(String.format("%d", sharedPref.getInt("port", 80)));
                    layout.addView(portBox);

                    final AlertDialog.Builder alert = new AlertDialog.Builder(MainActivity.this);
                    alert.setTitle("Connect").setView(layout);
                    alert.setPositiveButton("Connect", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            HostPort hp = new HostPort();
                            hp.host = hostBox.getText().toString();
                            hp.port = Integer.parseInt(portBox.getText().toString());

                            sharedPref.edit().putString("host", hp.host).putInt("port", hp.port).commit();
                            new PerformConnect().execute(hp);
                        }
                    });
                    alert.setNegativeButton("Cancel", null);
                    alert.show();
                }
            }
        });
        
        Timer t = new Timer();
        t.schedule(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                            txtBytesTotal.setText(totalBytes.toString());
                            if (!dataSample.equals(displayedDataSample)) {
                                txtContents.setText(dataSample);
                                displayedDataSample = dataSample;
                            }
                    }
                });
            }
        }, 0, 2000);
    }

    private class HostPort {
        public String host;
        public int port;
    }

    private class PerformConnect extends AsyncTask<HostPort, Void, Boolean> {
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            btnConnect.setEnabled(false);
            btnConnect.setText("Connecting...");
            isDisconnecting = false;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);

            if (success) {
                new PerformReceiveAndLog().execute();
                btnConnect.setText("Disconnect");
            }
            else {
                btnConnect.setText("Connect");
            }
            btnConnect.setEnabled(true);
        }

        @Override
        protected Boolean doInBackground(HostPort... hostPorts) {
            for (HostPort hp : hostPorts) {
                totalBytes = 0L;
                dataSample = "";

                InetAddress serverAddr = null;
                try {
                    serverAddr = InetAddress.getByName(hp.host);
                } catch (UnknownHostException e) {
                    showError(String.format("Unknown host: %s", e.toString()));
                    return false;
                }
                try {
                    sock = new Socket(serverAddr, hp.port);
                } catch (IOException e) {
                    showError(String.format("Connect failed: %s", e.toString()));
                    return false;
                }

                /* We are connected, try to get a file */
                try {
                    File extDir = getExternalFilesDir(null);
                    recordingFile = File.createTempFile("tmprecord", "txt", extDir);
                    recordingFile.createNewFile();
                    recordingStream = new FileOutputStream(recordingFile);
                }
                catch (IOException e) {
                    try {
                        sock.close();
                        sock = null;
                    }
                    catch (IOException e2) {
                    }
                    showError(String.format("Failed to create temp file: %s", e.toString()));
                    return false;
                }
                return true;
            }
            return false;
        }
    }

    private class PerformReceiveAndLog extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... voids) {
            try {
                InputStream s = sock.getInputStream();
                byte buf[] = new byte[1024];
                int r;
                while ((r = s.read(buf)) > 0) {
                    if (totalBytes == 0) {
                        if (r != 1024)
                            dataSample = new String(Arrays.copyOfRange(buf, 0, r));
                        else
                            dataSample = new String(buf);
                    }
                    recordingStream.write(buf, 0, r);
                    totalBytes += r;
                }
            } catch (IOException e) {
                if (!isDisconnecting)
                    showError(String.format("IO error: %s", e.toString()));
            } finally {
                try {
                    sock.close();
                    sock = null;
                }
                catch (IOException e) {
                }
                try {
                    recordingStream.flush();
                    recordingStream.close();
                    recordingStream = null;
                }
                catch (IOException e) {
                    showError(String.format("Flush errors: %s", e.toString()));
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            if (totalBytes == 0) {
                /* Never received anything, so just remove the file */
                recordingFile.delete();
                recordingFile = null;
            }
            else {
                new AlertDialog.Builder(MainActivity.this).setTitle("Save recording?")
                        .setMessage(String.format("A recording of %d bytes have been received. Save this?", totalBytes))
                        .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                final EditText filenameBox = new EditText(MainActivity.this);
                                filenameBox.setHint("Filename");
                                new AlertDialog.Builder(MainActivity.this).setTitle("Enter filename")
                                        .setView(filenameBox)
                                        .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialogInterface, int i) {
                                                File newf = new File(getExternalFilesDir(null), filenameBox.getText().toString());
                                                Log.i("files", String.format("Rename from %s to %s", recordingFile, newf));
                                                if (!recordingFile.renameTo(newf)) {
                                                    showError("Rename of file failed");
                                                }
                                                recordingFile = null;
                                            }
                                        })
                                        .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialogInterface, int i) {
                                                recordingFile.delete();
                                                recordingFile = null;
                                            }
                                        }).show();
                            }
                        })
                        .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                recordingFile.delete();
                                recordingFile = null;
                            }
                        }).show();
                totalBytes = 0L;
                dataSample = "";
            }
            btnConnect.setText("Connect");
        }
    }

    private void showError(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
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
}
