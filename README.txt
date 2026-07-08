
# Ernst Meier AG Web App (Android)

## Funcionalidades
- WebView carregando URL principal.
- Upload via câmera/galeria.
- Scanner ZXing com validação EAN-13.
- Ponte JavaScript: `Android.scanCode()` abre scanner.
- Resultado do scan enviado para `window.onScanResult(code)`.

## Como usar
1. Abra no Android Studio ou use script build_apk.bat.
2. Para chamar scanner via site:
```javascript
Android.scanCode();
window.onScanResult = function(code) {
    console.log('Código EAN:', code);
    document.querySelector('#meuInput').value = code;
};
```

## APK
Gerado em: `app/build/outputs/apk/debug/app-debug.apk`
