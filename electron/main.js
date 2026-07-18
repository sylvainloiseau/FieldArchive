// electron/main.js
const { app, BrowserWindow, shell, ipcMain, dialog } = require('electron');
const { spawn, execSync } = require('child_process');
const path = require('path');
const http = require('http');

let mainWindow;
let backendProcess;
const userDataPath = app.getPath('userData');


function killExistingBackendOnPort(port) {
  try {
    const pid = execSync(`lsof -ti :${port}`).toString().trim();
    if (pid) {
      console.log(`[Electron] Port ${port} déjà utilisé par PID ${pid}, arrêt...`);
      execSync(`kill -9 ${pid}`);
    }
  } catch (e) {
    console.log(`[Electron] Aucun process existant sur le port ${port} (ou lsof indisponible).`);
  }
}
// ─────────────────────────────────────────────
// 1. DÉMARRAGE DU BACKEND SPRING BOOT
// ─────────────────────────────────────────────

function startBackend() {
  killExistingBackendOnPort(8080);
  // __dirname = Projet-RDF-App_V1/electron/
  // On remonte d'un niveau pour atteindre la racine du projet
  const projectRoot = path.join(__dirname, '..');
  const jarPath = path.join(projectRoot, 'Backend', 'target', 'Backend-0.0.1-SNAPSHOT.jar');

  console.log('[Electron] Chemin JAR :', jarPath);
  console.log('[Electron] Démarrage du backend Spring Boot...');

  backendProcess = spawn('java', ['-jar', jarPath], {
    cwd: path.join(projectRoot, 'Backend'),
    stdio: 'pipe',
    env: {
      ...process.env,
      FIELD_ARCHIVE_DATA: userDataPath
    }

  });

  backendProcess.stdout.on('data', (data) => {
    console.log(`[Backend] ${data}`);
  });

  backendProcess.stderr.on('data', (data) => {
    console.error(`[Backend ERROR] ${data}`);
  });

  backendProcess.on('close', (code) => {
    console.log(`[Backend] Processus terminé avec le code : ${code}`);
  });
}

// ─────────────────────────────────────────────
// 2. ATTENDRE QUE LE BACKEND SOIT PRÊT
// ─────────────────────────────────────────────

function waitForBackend(url, maxRetries = 30, interval = 1000) {
  return new Promise((resolve, reject) => {
    let attempts = 0;

    const check = () => {
      http.get(url, (res) => {
        // Tout code < 500 signifie que Spring Boot répond (200, 404, etc.)
        if (res.statusCode < 500) {
          console.log(`[Electron] Backend prêt (HTTP ${res.statusCode})`);
          resolve();
        } else {
          retry();
        }
      }).on('error', retry);
    };

    const retry = () => {
      attempts++;
      console.log(`[Electron] Backend pas encore prêt, tentative ${attempts}/${maxRetries}...`);
      if (attempts >= maxRetries) {
        reject(new Error(`Backend non disponible après ${maxRetries} tentatives`));
      } else {
        setTimeout(check, interval);
      }
    };

    check();
  });
}

// ─────────────────────────────────────────────
// 3. CRÉATION DE LA FENÊTRE PRINCIPALE
// ─────────────────────────────────────────────

function createWindow() {
  mainWindow = new BrowserWindow({
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, 'electron-preload.js'),
      webSecurity: false   // ⚠️ allows file:// access
    }
  });

  // app.isPackaged = false en dev, true en production buildée
  // Pas besoin de définir NODE_ENV manuellement
  const isDev = !app.isPackaged;

  if (isDev) {
    // MODE DEV : Angular doit tourner sur ng serve (port 4200)
    console.log('[Electron] Mode développement — chargement depuis http://localhost:4200');
    mainWindow.loadURL('http://localhost:4200');
    mainWindow.webContents.openDevTools();
  } else {
    // MODE PROD : charger le build Angular statique
    const indexPath = path.join(__dirname, 'dist', 'frontend', 'browser', 'index.html');
    console.log('[Electron] Mode production — chargement depuis', indexPath);
    mainWindow.loadFile(indexPath);
  }

  // Ouvrir les liens externes dans le navigateur système
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

// ─────────────────────────────────────────────
// 5. IPC - FILE SELECTION
// ─────────────────────────────────────────────

ipcMain.handle('select-file', async () => {
  const result = await dialog.showOpenDialog({
    properties: ['openFile'],
  });

  if (result.canceled || result.filePaths.length === 0) {
    return null;
  }

  return result.filePaths[0]; // ✅ FULL DESKTOP PATH
});

// ─────────────────────────────────────────────
// 5b. IPC - FILE EXPORT (SAVE)
// ─────────────────────────────────────────────

ipcMain.handle('save-file', async (event, data, suggestedName) => {
  const win = BrowserWindow.fromWebContents(event.sender);

  const { canceled, filePath } = await dialog.showSaveDialog(win, {
    defaultPath: suggestedName,
    filters: [{ name: 'Turtle', extensions: ['ttl'] }],
  });

  if (canceled || !filePath) {
    return { success: false, canceled: true };
  }

  try {
    const fs = require('fs');
    fs.writeFileSync(filePath, Buffer.from(data));
    return { success: true, filePath };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

// ─────────────────────────────────────────────
// 4. CYCLE DE VIE D'ELECTRON
// ─────────────────────────────────────────────

app.whenReady().then(async () => {
  try {
    startBackend();

    console.log('[Electron] Attente du démarrage du backend...');
    await waitForBackend('http://localhost:8080/api/datasources');

    createWindow();

  } catch (err) {
    console.error('[Electron] Erreur de démarrage :', err.message);
    console.warn('[Electron] Ouverture de la fenêtre malgré l\'erreur backend...');
    createWindow();
  }
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createWindow();
  }
});

app.on('before-quit', () => {
  if (backendProcess) {
    console.log('[Electron] Arrêt du backend...');
    backendProcess.kill();
  }
});
