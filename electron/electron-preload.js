// electron/preload.js
// Script de préchargement — expose une API sécurisée au renderer (Angular)
// Utilise contextBridge pour la sécurité (pas d'accès direct à Node.js depuis Angular)

const { contextBridge, ipcRenderer, webUtils } = require('electron');

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
  selectFile: () => ipcRenderer.invoke('select-file'),
  
  saveFile: (data, suggestedName) => ipcRenderer.invoke('save-file', data, suggestedName),

  // Absolute path of a File chosen through an <input type="file"> in the renderer.
  // Replaces the removed File.path augmentation; must live here because the renderer
  // runs with contextIsolation and cannot require('electron') itself.
  getPathForFile: (file) => webUtils.getPathForFile(file),

});