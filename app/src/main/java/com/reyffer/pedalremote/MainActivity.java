package com.reyffer.pedalremote;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * Pedal Remote — a thin Android shell around the web page in assets/index.html.
 *
 * Why this exists: on many Android phones (Samsung included) the system's built-in USB-serial
 * driver grabs the ToneX One, and Chrome can't take it back. A normal app can, by "force claiming"
 * the pedal's serial interfaces. This class does only that USB plumbing; everything else
 * (screen, protocol, parameters) lives in the web page and is shared with the browser version.
 *
 * The page talks to this class through window.AndroidUsb, and we answer through
 * window.__usbEvent(type, message) and window.__usbData(base64).
 */
public class MainActivity extends Activity {

    private static final int VENDOR_ID = 0x1963;          // IK Multimedia
    private static final String ACTION_PERMISSION = "com.reyffer.pedalremote.USB_PERMISSION";

    private WebView web;
    private UsbManager usb;

    // the open connection (only touched while holding "lock")
    private final Object lock = new Object();
    private UsbDeviceConnection conn;
    private UsbDevice device;
    private UsbInterface commIf, dataIf;
    private UsbEndpoint epIn, epOut;
    private volatile boolean reading = false;
    private Thread readThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setBackgroundColor(0xFF0E0F12);
        web.setWebChromeClient(new WebChromeClient());   // lets the page show alert/confirm dialogs
        web.addJavascriptInterface(new Bridge(), "AndroidUsb");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
    }

    /** The pedal was plugged in while the app was already open (see the USB filter in the manifest). */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) event("attached", "");
    }

    @Override
    protected void onDestroy() {
        closeConnection(false);
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);   // keep the connection rather than closing the app by accident
    }

    // ---------------------------------------------------------------- talking to the page

    private void event(String type, String message) {
        final String js = "window.__usbEvent && window.__usbEvent(" + quote(type) + "," + quote(message) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private void data(byte[] bytes, int len) {
        final String b64 = Base64.encodeToString(bytes, 0, len, Base64.NO_WRAP);
        runOnUiThread(() -> web.evaluateJavascript("window.__usbData && window.__usbData('" + b64 + "')", null));
    }

    private static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'";
    }

    // ---------------------------------------------------------------- USB

    private UsbDevice findPedal() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == VENDOR_ID) return d;
        }
        return null;
    }

    private void startOpen() {
        UsbDevice d = findPedal();
        if (d == null) { event("error", "No ToneX pedal found. Check the cable is plugged in at both ends."); return; }
        if (usb.hasPermission(d)) { openDevice(d); return; }

        event("log", "Asking for permission to use the pedal");
        Intent i = new Intent(ACTION_PERMISSION).setPackage(getPackageName());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
    }

    private void openDevice(UsbDevice d) {
        synchronized (lock) {
            closeConnection(false);

            // Find the serial (CDC) interfaces: control = class 2, data = the one with bulk in + out
            UsbInterface comm = null, data = null;
            UsbEndpoint in = null, out = null;
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface itf = d.getInterface(i);
                if (itf.getInterfaceClass() == UsbConstants.USB_CLASS_COMM && comm == null) comm = itf;
                UsbEndpoint bi = null, bo = null;
                for (int e = 0; e < itf.getEndpointCount(); e++) {
                    UsbEndpoint ep = itf.getEndpoint(e);
                    if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) bi = ep; else bo = ep;
                }
                if (bi != null && bo != null && (data == null || itf.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA)) {
                    data = itf; in = bi; out = bo;
                }
            }
            if (data == null) { event("error", "Couldn't find the pedal's data connection."); return; }

            UsbDeviceConnection c = usb.openDevice(d);
            if (c == null) { event("error", "Android wouldn't open the pedal (permission missing?)."); return; }

            // "true" = force: take the interface away from the system's own serial driver.
            // This is the step Chrome can't do.
            if (!c.claimInterface(data, true)) { c.close(); event("error", "Couldn't claim the pedal's data connection."); return; }
            if (comm != null && comm != data) {
                if (c.claimInterface(comm, true)) {
                    // 115200 baud, 1 stop bit, no parity, 8 data bits; then DTR + RTS on
                    byte[] lineCoding = { 0x00, (byte) 0xC2, 0x01, 0x00, 0x00, 0x00, 0x08 };
                    c.controlTransfer(0x21, 0x20, 0, comm.getId(), lineCoding, lineCoding.length, 1000);
                    c.controlTransfer(0x21, 0x22, 0x03, comm.getId(), null, 0, 1000);
                } else {
                    event("log", "Couldn't claim the control interface; carrying on without it.");
                }
            }

            conn = c; device = d; commIf = comm; dataIf = data; epIn = in; epOut = out;
            event("log", "USB: data interface " + data.getId() + ", endpoints in " + in.getEndpointNumber() + " / out " + out.getEndpointNumber());
            startReading();
            event("opened", d.getProductName() == null ? "ToneX" : d.getProductName());
        }
    }

    private void startReading() {
        reading = true;
        final UsbDeviceConnection c = conn;
        final UsbEndpoint in = epIn;
        readThread = new Thread(() -> {
            byte[] buf = new byte[16384];
            while (reading) {
                int n = c.bulkTransfer(in, buf, buf.length, 250);   // returns -1 on timeout too
                if (n > 0) data(buf, n);
            }
        }, "pedal-read");
        readThread.start();
    }

    private boolean write(byte[] bytes) {
        synchronized (lock) {
            if (conn == null) return false;
            int sent = 0;
            while (sent < bytes.length) {
                int chunk = Math.min(512, bytes.length - sent);
                int n = conn.bulkTransfer(epOut, bytes, sent, chunk, 1000);
                if (n <= 0) return false;
                sent += n;
            }
            return true;
        }
    }

    private void closeConnection(boolean notify) {
        synchronized (lock) {
            reading = false;
            if (readThread != null) { try { readThread.join(500); } catch (InterruptedException ignored) {} readThread = null; }
            if (conn != null) {
                try { if (dataIf != null) conn.releaseInterface(dataIf); } catch (Exception ignored) {}
                try { if (commIf != null && commIf != dataIf) conn.releaseInterface(commIf); } catch (Exception ignored) {}
                conn.close();
                conn = null;
                if (notify) event("closed", "");
            }
            device = null; commIf = null; dataIf = null; epIn = null; epOut = null;
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (ACTION_PERMISSION.equals(intent.getAction())) {
                if (d != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) openDevice(d);
                else event("error", "Permission to use the pedal was refused.");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                if (d != null && device != null && d.getDeviceName().equals(device.getDeviceName())) {
                    closeConnection(true);
                }
            }
        }
    };

    // ---------------------------------------------------------------- what the page can call

    private class Bridge {
        @JavascriptInterface public boolean hasPedal() { return findPedal() != null; }
        @JavascriptInterface public void open() { runOnUiThread(MainActivity.this::startOpen); }
        @JavascriptInterface public boolean write(String base64) { return MainActivity.this.write(Base64.decode(base64, Base64.NO_WRAP)); }
        @JavascriptInterface public void close() { closeConnection(true); }
        @JavascriptInterface public String version() { return BuildConfig.VERSION_NAME; }
    }
}
