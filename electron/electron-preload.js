// electron/preload.js
// Script de préchargement — expose une API sécurisée au renderer (Angular)
// Utilise contextBridge pour la sécurité (pas d'accès direct à Node.js depuis Angular)

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  getVersion: () => process.versions.electron,

  send: (channel, data) => {
    const allowedChannels = ['app-ready', 'open-file'];
    if (allowedChannels.includes(channel)) {
      ipcRenderer.send(channel, data);
    }
  },

  receive: (channel, callback) => {
    const allowedChannels = ['backend-status'];
    if (allowedChannels.includes(channel)) {
      ipcRenderer.on(channel, (event, ...args) => callback(...args));
    }
  },

  // ✅ NEW FEATURE
  selectFile: () => ipcRenderer.invoke('select-file')
});