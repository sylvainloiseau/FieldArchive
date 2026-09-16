export {};

declare global {
  interface Window {
    // Absent when the app runs in a plain browser rather than inside Electron.
    electronAPI?: {
      selectFile: () => Promise<string | null>;
      // Only available from Electron 29 onwards; guard before calling.
      getPathForFile?: (file: File) => string;
    };
  }
}
