// electron/preload.js
// Script de préchargement — expose une API sécurisée au renderer (Angular)
// Utilise contextBridge pour la sécurité (pas d'accès direct à Node.js depuis Angular)

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  // Exemple : récupérer la version d'Electron depuis Angular
  getVersion: () => process.versions.electron,

  // Exemple : envoyer un message au processus principal
  send: (channel, data) => {
    const allowedChannels = ['app-ready', 'open-file'];
    if (allowedChannels.includes(channel)) {
      ipcRenderer.send(channel, data);
    }
  },

  // Exemple : recevoir un message du processus principal
  receive: (channel, callback) => {
    const allowedChannels = ['backend-status'];
    if (allowedChannels.includes(channel)) {
      ipcRenderer.on(channel, (event, ...args) => callback(...args));
    }
  }
});
