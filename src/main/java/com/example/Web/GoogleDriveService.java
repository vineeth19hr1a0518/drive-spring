package com.example.Web;


import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequest; // Import HttpRequest
import com.google.api.client.http.HttpRequestInitializer; // Import HttpRequestInitializer
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter; // Correct import for HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream; // Added import
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets; // Added import
import java.security.GeneralSecurityException;
import java.time.Month;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;



@Service
public class GoogleDriveService {

    // Path to the service account JSON key file (for local development fallback)
    // Made optional with ":" so it doesn't fail if the property isn't set (when using env var)
    @Value("${google.drive.service-account-key-path:}")
    private String serviceAccountKeyPath;

    // Inject the JSON key content directly from an environment variable (for production)
    // If GOOGLE_APPLICATION_CREDENTIALS_JSON_STRING is empty/not set, it will be an empty string.
    @Value("${GOOGLE_APPLICATION_CREDENTIALS_JSON_STRING:}")
    private String serviceAccountKeyJsonString;

    @Value("${google.drive.root-folder-id}")
    private String rootFolderId;

    @Value("${google.drive.application-name}")
    private String applicationName;

    private Drive driveService;
    private final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

    /**
     * Initializes the Google Drive service client using the service account credentials.
     * This method is called automatically after the bean's properties have been set.
     * It prioritizes reading credentials from an environment variable for production,
     * falling back to a classpath file for local development.
     * @throws IOException If the service account key cannot be read from either source.
     * @throws GeneralSecurityException If there's a problem with cryptographic operations.
     */
    @PostConstruct
    public void init() throws IOException, GeneralSecurityException {
        InputStream serviceAccountStream = null;
        try {
            if (serviceAccountKeyJsonString != null && !serviceAccountKeyJsonString.isEmpty()) {
                // Production (or when env var is set): Read JSON key from environment variable string
                serviceAccountStream = new ByteArrayInputStream(serviceAccountKeyJsonString.getBytes(StandardCharsets.UTF_8));
                System.out.println("Using service account credentials from environment variable.");
            } else if (serviceAccountKeyPath != null && !serviceAccountKeyPath.isEmpty()) {
                // Local Development Fallback: Read JSON key from classpath file
                ClassPathResource resource = new ClassPathResource(serviceAccountKeyPath);
                serviceAccountStream = resource.getInputStream();
                System.out.println("Using service account credentials from classpath file: " + serviceAccountKeyPath);
            } else {
                throw new IOException("Google service account credentials not found. Neither environment variable 'GOOGLE_APPLICATION_CREDENTIALS_JSON_STRING' nor 'google.drive.service-account-key-path' property is configured.");
            }

            GoogleCredentials credentials = GoogleCredentials.fromStream(serviceAccountStream)
                    .createScoped(Collections.singleton(DriveScopes.DRIVE));

            HttpRequestInitializer requestInitializer = new HttpRequestInitializer() {
                final HttpRequestInitializer originalInitializer = new HttpCredentialsAdapter(credentials);

                @Override
                public void initialize(HttpRequest httpRequest) throws IOException {
                    originalInitializer.initialize(httpRequest); // Apply the credentials
                    httpRequest.setConnectTimeout(60000); // 60 seconds connection timeout
                    httpRequest.setReadTimeout(60000);    // 60 seconds read timeout
                }
            };

            driveService = new Drive.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    JSON_FACTORY,
                    requestInitializer) // Pass the custom HttpRequestInitializer
                    .setApplicationName(applicationName)
                    .build();
        } finally {
            if (serviceAccountStream != null) {
                serviceAccountStream.close();
            }
        }
    }

    /**
     * Uploads a sales data file to Google Drive, organizing it into a dynamic folder structure:
     * rootFolderId / MonthName / Market / Country / Brand-FromDate_ToDate.csv
     *
     * @param file The MultipartFile (CSV) to upload.
     * @param monthNumber The numerical month (e.g., "06" for June).
     * @param market The market name (e.g., "Amazon").
     * @param country The country code (e.g., "US").
     * @param brand The brand name (e.g., "Bryco").
     * @param fromDate The start date of the data range (YYYY-MM-DD format).
     * @param toDate The end date of the data range (YYYY-MM-DD format).
     * @return The ID of the newly uploaded file in Google Drive.
     * @throws IOException If any I/O error occurs during file processing or API interaction.
     */
    public String uploadSalesDataFile(
            MultipartFile file,
            String monthNumber,
            String market,
            String country,
            String brand,
            String fromDate,
            String toDate) throws IOException {

        // 1. Validate file type (basic client-side check is not enough, reinforce here)
        if (!"text/csv".equals(file.getContentType()) && !file.getOriginalFilename().toLowerCase().endsWith(".csv")) {
            throw new IOException("Only CSV files are allowed.");
        }

        // 2. Convert month number to full month name (e.g., "06" -> "June")
        String monthName = Month.of(Integer.parseInt(monthNumber)).getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);

        // 3. Find or create the Month folder
        String monthFolderId = findOrCreateFolder(rootFolderId, monthName);

        // 4. Find or create the Market folder within the Month folder
        String marketFolderId = findOrCreateFolder(monthFolderId, market);

        // 5. Find or create the Country folder within the Market folder
        String countryFolderId = findOrCreateFolder(marketFolderId, country);

        // 6. Construct the new file name: Brand-FromDate_ToDate.csv
        String newFileName = String.format("%s-%s_%s.csv", brand, fromDate, toDate);

        // 7. Prepare file content for upload
        InputStreamContent mediaContent = new InputStreamContent(file.getContentType(), file.getInputStream());

        // 8. Prepare file metadata for Google Drive
        File fileMetadata = new File();
        fileMetadata.setName(newFileName);
        fileMetadata.setMimeType(file.getContentType());
        fileMetadata.setParents(Collections.singletonList(countryFolderId)); // Upload to the Country folder

        // 9. Execute the file upload
        File uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id, name, webContentLink, webViewLink, mimeType, size") // Request necessary fields
                .execute();

        return uploadedFile.getId(); // Return the ID of the uploaded file
    }


    /**
     * Helper method to find an existing folder by name within a parent folder,
     * or create it if it doesn't exist.
     *
     * @param parentId The ID of the parent folder to search within.
     * @param folderName The name of the folder to find or create.
     * @return The ID of the found or newly created folder.
     * @throws IOException If an I/O error occurs during API interaction.
     */
    private String findOrCreateFolder(String parentId, String folderName) throws IOException {
        // Query for a folder with the given name and MIME type within the parent folder
        String query = String.format("name = '%s' and '%s' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false", folderName, parentId);

        FileList result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)") // Only need ID and name
                .execute();

        List<File> files = result.getFiles();

        if (files != null && !files.isEmpty()) {
            // Folder found, return its ID
            return files.get(0).getId();
        } else {
            // Folder not found, create a new one
            File fileMetadata = new File();
            fileMetadata.setName(folderName);
            fileMetadata.setMimeType("application/vnd.google-apps.folder");
            fileMetadata.setParents(Collections.singletonList(parentId));

            File createdFolder = driveService.files().create(fileMetadata)
                    .setFields("id, name")
                    .execute();
            return createdFolder.getId();
        }
    }


    // Existing methods (keep as is, or modify as needed for your application)
    // ---
    /**
     * Downloads a file from Google Drive.
     * @param fileId The ID of the file to download.
     * @return Byte array of the file content.
     * @throws IOException If an I/O error occurs.
     */
    public byte[] downloadFile(String fileId) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        driveService.files().get(fileId).executeMediaAndDownloadTo(outputStream);
        return outputStream.toByteArray();
    }

    /**
     * Lists files within a specific folder.
     * @param folderId The ID of the folder to list files from. If null, uses root folder.
     * @return A list of file metadata.
     * @throws IOException If an I/O error occurs.
     */
    public List<DriveFileMetadata> listFilesInFolder(String folderId) throws IOException {
        String queryFolderId = (folderId != null && !folderId.isEmpty()) ? folderId : rootFolderId;
        String query = "'" + queryFolderId + "' in parents and trashed = false";

        FileList result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("nextPageToken, files(id, name, mimeType, size, webContentLink, webViewLink, createdTime, modifiedTime)")
                .execute();

        return result.getFiles().stream()
                .map(file -> new DriveFileMetadata(
                        file.getId(),
                        file.getName(),
                        file.getMimeType(),
                        file.getSize(),
                        file.getWebContentLink(),
                        file.getWebViewLink(),
                        // Correctly convert com.google.api.client.util.DateTime to Long (milliseconds)
                        file.getCreatedTime() != null ? file.getCreatedTime().getValue() : null,
                        file.getModifiedTime() != null ? file.getModifiedTime().getValue() : null
                ))
                .collect(Collectors.toList());
    }

    /**
     * Renames a file in Google Drive.
     * @param fileId The ID of the file to rename.
     * @param newName The new name for the file.
     * @throws IOException If an I/O error occurs.
     */
    public void renameFile(String fileId, String newName) throws IOException {
        File fileMetadata = new File();
        fileMetadata.setName(newName);
        driveService.files().update(fileId, fileMetadata).execute();
    }

    /**
     * Deletes a file from Google Drive.
     * @param fileId The ID of the file to delete.
     * @throws IOException If an I/O error occurs.
     */
    public void deleteFile(String fileId) throws IOException {
        driveService.files().delete(fileId).execute();
    }

}