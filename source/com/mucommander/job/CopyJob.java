
package com.mucommander.job;

import com.mucommander.file.*;

import com.mucommander.ui.MainFrame;
import com.mucommander.ui.ProgressDialog;
import com.mucommander.ui.table.FileTable;
import com.mucommander.ui.FileExistsDialog;

import com.mucommander.text.Translator;

import java.util.Vector;

import java.io.IOException;


/**
 * This job recursively copies (or unzips) a group of files.
 *
 * @author Maxence Bernard
 */
public class CopyJob extends ExtendedFileJob implements Runnable {

	/** New filename in destination */
	private String newName;

	/** Default choice when encountering an existing file */
	private int defaultFileExistsChoice = -1;

	/** Title used for error dialogs */
	private String errorDialogTitle;
	
	/** if true, files will be unzipped in the destination folder instead of being copied */
	private boolean unzipMode;
	
	
    /**
	 * Creates a new CopyJob without starting it.
	 *
	 * @param progressDialog dialog which shows this job's progress
	 * @param mainFrame mainFrame this job has been triggered by
	 * @param files files which are going to be copied
	 * @param destFolder destination folder where the files will be copied
	 * @param newName the new filename in the destination folder, can be <code>null</code> in which case the original filename will be used.
	 * @param unzipMode if true, files will be unzipped in the destination folder instead of being copied.
	 */
	public CopyJob(ProgressDialog progressDialog, MainFrame mainFrame, Vector files, AbstractFile destFolder, String newName, boolean unzipMode) {
		super(progressDialog, mainFrame, files, destFolder);

		this.newName = newName;
		this.unzipMode = unzipMode;
		this.errorDialogTitle = Translator.get(unzipMode?"unzip_dialog.error_title":"copy_dialog.error_title");
	}

	
	/////////////////////////////////////
	// Abstract methods Implementation //
	/////////////////////////////////////

    protected boolean processFile(AbstractFile file, AbstractFile destFolder, Object recurseParams[]) {
		if(isInterrupted())
            return false;
		
		// Notify job that we're starting to process this file
		nextFile(file);

		// Is current file at the base folder level ?
		boolean isFileInBaseFolder = file.getParent().equals(baseSourceFolder);

		// If in unzip mode, unzip base source folder's zip files
		if(unzipMode && isFileInBaseFolder) {
			// If unzip mode and file is not a ZipArchiveFile (happens when extension is not .zip)
			if(!(file instanceof ZipArchiveFile))
				file = new ZipArchiveFile(file);
			
			do {
				try {
					// Recurse on zip's contents
					AbstractFile zipSubFiles[] = currentFile.ls();
					for(int j=0; j<zipSubFiles.length; j++)
						processFile(zipSubFiles[j], destFolder, null);
					return true;
				}
				catch(IOException e) {
					// File could not be uncompressed properly
					int ret = showErrorDialog(errorDialogTitle, Translator.get("unzip.unable_to_open_zip", currentFile.getName()));
					// Retry loops
					if(ret==RETRY_ACTION)
						continue;
					// cancel, skip or close dialog will simply return false
					return false;
				}
			} while(true);
		}

		// Determine filename in destination
		String originalName = file.getName();
		String destFileName;
		if(isFileInBaseFolder && newName!=null)
			destFileName = newName;
       	else
			destFileName = originalName;
		
		// Destination file
		AbstractFile destFile = AbstractFile.getAbstractFile(destFolder.getAbsolutePath(true)+destFileName);

		// Do nothing if file is a symlink (skip file and return)
		if(file.isSymlink())
			return true;
		// Copy directory recursively
        else if(file.isDirectory()) {
            // Create the folder in the destination folder if it doesn't exist
			if(!(destFile.exists() && destFile.isDirectory())) {
				// Loop for retry
				do {
					try {
						destFolder.mkdir(destFileName);
					}
					catch(IOException e) {
						// Unable to create folder
						int ret = showErrorDialog(errorDialogTitle, Translator.get("cannot_create_folder", destFileName));
						// Retry loops
						if(ret==RETRY_ACTION)
							continue;
						// Cancel or close dialog return false
						return false;
						// Skip continues
					}
					break;
				} while(true);
			}
			
			// and copy each file in this folder recursively
			do {		// Loop for retry
				try {
					// for each file in folder...
					AbstractFile subFiles[] = file.ls();
					for(int i=0; i<subFiles.length && !isInterrupted(); i++)
						processFile(subFiles[i], destFile, null);
					return true;
				}
				catch(IOException e) {
					// Unable to open source file
					int ret = showErrorDialog(errorDialogTitle, Translator.get("cannot_read_folder", destFile.getName()));
					// Retry loops
					if(ret==RETRY_ACTION)
						continue;
					// Cancel, skip or close dialog return false
					return false;
				}
			} while(true);
        }
		// Copy file
        else  {
			boolean append = false;
			
			// Tests if the file already exists in destination
			// and if it does, ask the user what to do or
			// use a previous user global answer.
			if (destFile.exists())  {
				int choice;
				// No default choice
				if(defaultFileExistsChoice==-1) {
					FileExistsDialog dialog = getFileExistsDialog(file, destFile);
					choice = waitForUserResponse(dialog);
					// If 'apply to all' was selected, this choice will be used
					// for any files that already exist  (user will not be asked again)
					if(dialog.applyToAllSelected())
						defaultFileExistsChoice = choice;
				}
				// Use previous choice
				else
					choice = defaultFileExistsChoice;
				
				// Cancel, skip or close dialog
				if (choice==-1 || choice==FileExistsDialog.CANCEL_ACTION) {
					stop();
					return false;
				}
				// Skip file
				else if (choice==FileExistsDialog.SKIP_ACTION) {
					return false;
				}
				// Append to file (resume file copy)
				else if (choice==FileExistsDialog.APPEND_ACTION) {
					append = true;
				}
				// Overwrite file 
				else if (choice==FileExistsDialog.OVERWRITE_ACTION) {
					// Do nothing, simply continue
				}
				//  Overwrite file if destination is older
				else if (choice==FileExistsDialog.OVERWRITE_IF_OLDER_ACTION) {
					// Overwrite if file is newer (stricly)
					if(file.getDate()<=destFile.getDate())
						return true;
				}
			}

			return tryCopyFile(file, destFile, append, errorDialogTitle);
/*			
			// Copy file to destination
			do {				// Loop for retry
				try {
					copyFile(file, destFile, append);
				}
				catch(FileJobException e) {
					// Copy failed
					if(com.mucommander.Debug.ON)
						System.out.println(""+e);
					
					int reason = e.getReason();
					String errorMsg;
					switch(reason) {
						// Could not open source file for read
						case FileJobException.CANNOT_OPEN_SOURCE:
							errorMsg = Translator.get("cannot_read_source", file.getName());
							break;
						// Could not open destination file for write
						case FileJobException.CANNOT_OPEN_DESTINATION:
							errorMsg = Translator.get("cannot_write_destination", file.getName());
							break;
						// An error occurred during file transfer
						case FileJobException.ERROR_WHILE_TRANSFERRING:
						default:
							errorMsg = Translator.get("error_while_transferring", file.getName());
							break;
					}
					
					// Ask the user what to do
					int ret = showErrorDialog(errorDialogTitle, errorMsg);
					if(ret==RETRY_ACTION) {
						// Resume transfer
						if(reason==FileJobException.ERROR_WHILE_TRANSFERRING)
							append = true;
						continue;
					}
					else if(ret==-1 || ret==CANCEL_ACTION)		// CANCEL_ACTION or close dialog
						stop();
				}
				break;
			} while(true);
*/
		}
	}

    public String getStatusString() {
        return Translator.get(unzipMode?"unzip.unzipping_file":"copy.copying_file", getCurrentFileInfo());
    }
}
