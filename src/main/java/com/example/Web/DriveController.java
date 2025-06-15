package com.example.Web;


import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/drive")
public class DriveController {

    private final GoogleDriveService googleDriveService;

    public DriveController(GoogleDriveService googleDriveService) {
        this.googleDriveService = googleDriveService;
    }

    /**
     * Handles file upload with dynamic folder creation and file renaming.
     *
     * @param file The CSV file to upload.
     * @param month The selected month (e.g., "01" for January).
     * @param market The selected market (e.g., "Amazon").
     * @param country The selected country (e.g., "US").
     * @param brand The selected brand (e.g., "Bryco").
     * @param fromDate The start date (YYYY-MM-DD).
     * @param toDate The end date (YYYY-MM-DD).
     * @return ResponseEntity with success message or error.
     */
    @PostMapping("/upload")
    public ResponseEntity<String> uploadSalesData(
            @RequestParam("file") MultipartFile file,
            @RequestParam("month") String month,
            @RequestParam("market") String market,
            @RequestParam("country") String country,
            @RequestParam("brand") String brand,
            @RequestParam("fromDate") String fromDate,
            @RequestParam("toDate") String toDate) {
        try {
            String fileId = googleDriveService.uploadSalesDataFile(file, month, market, country, brand, fromDate, toDate);
            System.out.println(month);
            return new ResponseEntity<>("File uploaded and organized successfully. Google Drive File ID: " + fileId, HttpStatus.OK);
        } catch (IOException e) {
            // Log the exception for debugging purposes
            e.printStackTrace();
            return new ResponseEntity<>("Failed to upload file: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (Exception e) {
            // Catch any other unexpected exceptions
            e.printStackTrace();
            return new ResponseEntity<>("An unexpected error occurred: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // Existing methods (keep as is for now)
    // ---
    @GetMapping("/download/{fileId}")
    public ResponseEntity<byte[]> downloadFile(@PathVariable String fileId) {
        try {
            byte[] fileContent = googleDriveService.downloadFile(fileId);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM); // Generic binary type

            // OPTIONAL: Fetch actual file name from Drive to set correct Content-Disposition
            // For now, using a generic name or assuming you can get it from an earlier list operation
            headers.setContentDispositionFormData("attachment", "downloaded_file");

            return new ResponseEntity<>(fileContent, headers, HttpStatus.OK);
        } catch (IOException e) {
            e.printStackTrace(); // Log for debugging
            return new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/list")
    public ResponseEntity<List<DriveFileMetadata>> listFilesInFolder(
            @RequestParam(value = "folderId", required = false) String folderId) {
        try {
            List<DriveFileMetadata> files = googleDriveService.listFilesInFolder(folderId);
            return new ResponseEntity<>(files, HttpStatus.OK);
        } catch (IOException e) {
            e.printStackTrace(); // Log for debugging
            return new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PutMapping("/rename/{fileId}")
    public ResponseEntity<String> renameFile(
            @PathVariable String fileId,
            @RequestParam("newName") String newName) {
        try {
            googleDriveService.renameFile(fileId, newName);
            return new ResponseEntity<>("File renamed successfully.", HttpStatus.OK);
        } catch (IOException e) {
            e.printStackTrace(); // Log for debugging
            return new ResponseEntity<>("Failed to rename file: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @DeleteMapping("/delete/{fileId}")
    public ResponseEntity<String> deleteFile(@PathVariable String fileId) {
        try {
            googleDriveService.deleteFile(fileId);
            return new ResponseEntity<>("File deleted successfully.", HttpStatus.OK);
        } catch (IOException e) {
            e.printStackTrace(); // Log for debugging
            return new ResponseEntity<>("Failed to delete file: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
