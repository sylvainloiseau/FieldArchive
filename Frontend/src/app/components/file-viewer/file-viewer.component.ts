import { Component, Output, EventEmitter, Inject, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

type FileType = 'image' | 'video' | 'audio' | 'pdf' | 'unknown';

import {MAT_DIALOG_DATA, MatDialogRef} from '@angular/material/dialog';

@Component({
  selector: 'app-file-viewer',
  imports: [CommonModule, FormsModule],
  templateUrl: './file-viewer.component.html',
  styleUrl: './file-viewer.component.scss'
})
export class FileViewerComponent implements OnInit {

  filePath: string = '';

  public data: any = inject(MAT_DIALOG_DATA, { optional: true });
  private dialogRef: MatDialogRef<FileViewerComponent> | null = inject(MatDialogRef, { optional: true });
  public isDialogRoot: boolean = false;

  @Output() filePathChange = new EventEmitter<string>();

  fileUrl: string = '';
  safeUrl: SafeResourceUrl | null = null; // important to avoid security errors from Angular when using the URL in an iframe or video/audio tag

  fileType: FileType = 'unknown';

  constructor(private sanitizer: DomSanitizer,
    // @Inject(MAT_DIALOG_DATA) public data: any,
    // private dialogRef: MatDialogRef<FileViewerComponent>,

  ) {}

  ngOnInit() {
    this.isDialogRoot = this.dialogRef?.componentInstance === this; 

    if (this.isDialogRoot) {
      this.openFile(this.data);
    }
  }

  detectFileType(path: string): FileType {
    const ext = path.split('.').pop()?.toLowerCase();

    if (!ext) return 'unknown';

    if (['png','jpg','jpeg','gif','webp'].includes(ext)) return 'image';
    if (['mp4','webm','ogg'].includes(ext)) return 'video';
    if (['mp3','wav','ogg'].includes(ext)) return 'audio';
    if (ext === 'pdf') return 'pdf';

    return 'unknown';
  }

  async openFile(pathParam: string) {

    let path = pathParam !== '' ? pathParam : await window.electronAPI.selectFile();

    if (path) {
      this.filePathChange.emit(path);
      this.filePath = path;

      this.fileUrl = `file:///${path.replace(/\\/g, '/')}`;

      //sanitize for iframe/video/audio usage
      this.safeUrl = this.sanitizer.bypassSecurityTrustResourceUrl(this.fileUrl);

      this.fileType = this.detectFileType(path);

    }
  }

  closeAndReturn() {
    console.log('closeAndReturn CALLED, dialogRef:', this.dialogRef, 'filePath:', this.filePath);
    if (this.dialogRef) {
      this.dialogRef.close(this.filePath); 
    }
  }


}