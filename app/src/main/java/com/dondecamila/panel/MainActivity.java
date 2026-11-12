package com.dondecamila.panel;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Panel Dondecamila: abre el panel web y le da una impresora Bluetooth directa.
 * La página llama a window.DCPrinter.imprimir(base64) y la app manda esos bytes
 * (texto ESC/POS) a la impresora térmica, igual que Zobaze: sin RawBT y sin imágenes.
 */
public class MainActivity extends Activity {

    private static final String PANEL = "https://dondecamila.vercel.app/admin";
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int REQ_BLUETOOTH = 1;
    private static final int REQ_ARCHIVO = 2;

    private WebView web;
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> archivoCallback;
    private byte[] pendiente;            // ticket esperando a que se elija impresora / se dé permiso
    private volatile boolean imprimiendo = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("impresora", MODE_PRIVATE);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(s.getUserAgentString() + " DondecamilaApp/1.0");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Puente(), "DCPrinter");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost() == null ? "" : uri.getHost();
                // El panel y Firebase se quedan dentro de la app; lo demás (WhatsApp, etc.) se abre afuera
                if (host.endsWith("dondecamila.vercel.app") || host.endsWith("firebaseapp.com")
                        || host.endsWith("googleapis.com") || host.endsWith("gstatic.com")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    aviso("No se pudo abrir el enlace");
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // Permite elegir fotos al subir productos
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (archivoCallback != null) archivoCallback.onReceiveValue(null);
                archivoCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_ARCHIVO);
                } catch (Exception e) {
                    archivoCallback = null;
                    aviso("No se pudo abrir la galería");
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(PANEL);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ARCHIVO && archivoCallback != null) {
            archivoCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            archivoCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /* ------------------------------------------------------------------ */
    /* Puente con la página: window.DCPrinter                               */
    /* ------------------------------------------------------------------ */
    private class Puente {
        @JavascriptInterface
        public boolean disponible() {
            return true;
        }

        @JavascriptInterface
        public String impresora() {
            return prefs.getString("nombre", "");
        }

        @JavascriptInterface
        public void elegir() {
            runOnUiThread(() -> elegirImpresora(null));
        }

        @JavascriptInterface
        public void imprimir(String base64) {
            final byte[] datos;
            try {
                datos = Base64.decode(base64, Base64.DEFAULT);
            } catch (Exception e) {
                aviso("Ticket inválido");
                return;
            }
            runOnUiThread(() -> {
                String mac = prefs.getString("mac", null);
                if (mac == null || !tienePermiso()) elegirImpresora(datos);
                else enviar(mac, datos);
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /* Bluetooth                                                            */
    /* ------------------------------------------------------------------ */
    private boolean tienePermiso() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_BLUETOOTH) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            byte[] p = pendiente;
            pendiente = null;
            String mac = prefs.getString("mac", null);
            if (p != null && mac != null) enviar(mac, p);
            else elegirImpresora(p);
        } else {
            pendiente = null;
            aviso("Sin permiso de Bluetooth no se puede imprimir");
        }
    }

    /** Muestra las impresoras emparejadas; si hay un ticket pendiente, lo imprime al elegir. */
    @SuppressLint("MissingPermission")
    private void elegirImpresora(final byte[] luego) {
        if (!tienePermiso()) {
            pendiente = luego;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLUETOOTH);
            return;
        }
        BluetoothAdapter adaptador = BluetoothAdapter.getDefaultAdapter();
        if (adaptador == null) {
            aviso("Este equipo no tiene Bluetooth");
            return;
        }
        if (!adaptador.isEnabled()) {
            aviso("Activa el Bluetooth y vuelve a intentar");
            return;
        }
        Set<BluetoothDevice> emparejados = adaptador.getBondedDevices();
        if (emparejados == null || emparejados.isEmpty()) {
            aviso("Primero empareja la impresora en Ajustes > Bluetooth");
            return;
        }
        final List<BluetoothDevice> lista = new ArrayList<>(emparejados);
        final String[] nombres = new String[lista.size()];
        for (int i = 0; i < lista.size(); i++) {
            String n = lista.get(i).getName();
            nombres[i] = (n == null || n.isEmpty()) ? lista.get(i).getAddress() : n;
        }
        new AlertDialog.Builder(this)
                .setTitle("Elige la impresora")
                .setItems(nombres, (dialog, i) -> {
                    prefs.edit()
                            .putString("mac", lista.get(i).getAddress())
                            .putString("nombre", nombres[i])
                            .apply();
                    aviso("Impresora: " + nombres[i]);
                    if (luego != null) enviar(lista.get(i).getAddress(), luego);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Conecta por Bluetooth (perfil serie) y manda los bytes del ticket. */
    @SuppressLint("MissingPermission")
    private void enviar(final String mac, final byte[] datos) {
        if (imprimiendo) {
            aviso("Ya se está imprimiendo, espera un momento");
            return;
        }
        imprimiendo = true;
        aviso("Imprimiendo…");
        new Thread(() -> {
            BluetoothSocket socket = null;
            try {
                BluetoothAdapter adaptador = BluetoothAdapter.getDefaultAdapter();
                BluetoothDevice impresora = adaptador.getRemoteDevice(mac);
                socket = impresora.createRfcommSocketToServiceRecord(SPP);
                try {
                    socket.connect();
                } catch (IOException primero) {
                    try { socket.close(); } catch (IOException ignorado) { }
                    socket = impresora.createInsecureRfcommSocketToServiceRecord(SPP);
                    socket.connect();
                }
                OutputStream salida = socket.getOutputStream();
                // En partes pequeñas para no saturar la memoria de la impresora
                for (int i = 0; i < datos.length; i += 256) {
                    int n = Math.min(256, datos.length - i);
                    salida.write(datos, i, n);
                    salida.flush();
                    Thread.sleep(30);
                }
                Thread.sleep(800);   // dar tiempo a que termine antes de cortar la conexión
                aviso("Ticket impreso");
            } catch (Exception e) {
                aviso("No se pudo imprimir. ¿La impresora está encendida y cerca? Si otra app (Zobaze/RawBT) la está usando, ciérrala.");
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (IOException ignorado) { }
                }
                imprimiendo = false;
            }
        }).start();
    }

    private void aviso(final String texto) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, texto, Toast.LENGTH_LONG).show());
    }
}
