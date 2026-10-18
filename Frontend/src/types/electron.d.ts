export {};

declare global {
  interface Window {
    // Absent when the app runs in a plain browser rather than inside Electron.
    electronAPI?: {
      selectFile: () => Promise<string | null>;
      // Directory chooser; null when cancelled.
      selectDirectory?: () => Promise<string | null>;
      // Shows a directory in the OS file manager; '' on success, else an error message.
      openDirectory?: (dirPath: string) => Promise<string>;
      // Only available from Electron 29 onwards; guard before calling.
      getPathForFile?: (file: File) => string;
    };
  }
}
