package com.example.Web;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
// import org.springframework.core.io.ClassPathResource; // No longer strictly needed if only using FileInputStream for file system path
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Month;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

// NEW IMPORTS FOR FILE SYSTEM READING
import java.io.FileInputStream; // <-- NEW
import java.io.FileNotFoundException; // <-- NEW
import java.io.File; // <-- NEW

@Service
public class GoogleDriveService {

    // Inject the JSON key content directly from an environment variable (for production)
    // If GOOGLE_APPLICATION_CREDENTIALS_JSON_STRING is empty/not set, it will default to empty string.
    @Value("${GOOGLE_APPLICATION_CREDENTIALS_JSON_STRING:}")
    private String serviceAccountKeyJsonString;

    // Path to the service account JSON key file (for local development fallback OR Render Secret File)
    // This will now point to the /etc/secrets/google-credentials.json path
    @Value("${google.drive.service-account-key-path:/etc/secrets/google-credentials.json}") // Default to Render's secret path
    private String serviceAccountKeyPath;

    @Value("${google.drive.root-folder-id}")
    private String rootFolderId;

    @Value("${google.drive.application-name}")
    private String applicationName;

    private Drive driveService;
    private final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

    @PostConstruct
    public void init() throws IOException, GeneralSecurityException {
        InputStream serviceAccountStream = null;
        try {
            if (serviceAccountKeyJsonString != null && !serviceAccountKeyJsonString.isEmpty()) {
                // OPTION 1: Production (Recommended) - Read JSON key from environment variable string
                System.out.println("Attempting to load Google credentials from environment variable GOOGLE_APPLICATION_CREDENTIALS_JSON_STRING.");
                serviceAccountStream = new ByteArrayInputStream(serviceAccountKeyJsonString.getBytes(StandardCharsets.UTF_8));
            } else if (serviceAccountKeyPath != null && !serviceAccountKeyPath.isEmpty()) {
                // OPTION 2: Render Secret File or Local File System Path
                // This path should be "/etc/secrets/google-credentials.json" when on Render,
                // or a local path like "src/main/resources/google-credentials.json" for local dev
                java.io.File credentialsFile = new java.io.File(serviceAccountKeyPath); // Use java.io.File to avoid conflict with drive.model.File

                if (credentialsFile.exists() && credentialsFile.isFile()) {
                    System.out.println("Attempting to load Google credentials from file system path: " + serviceAccountKeyPath);
                    serviceAccountStream = new FileInputStream(credentialsFile);
                } else {
                    // Fallback for local development if credentials file is on classpath (less secure for production)
                    // This scenario is for when the file is literally *inside* your JAR
                    System.err.println("Google credentials file NOT found at file system path: " + serviceAccountKeyPath);
                    System.err.println("Attempting to load as a Class Path Resource (fallback for local dev if file is bundled in JAR)...");
                    // Use ClassLoader to get resource as stream for flexibility
                    serviceAccountStream = getClass().getClassLoader().getResourceAsStream(serviceAccountKeyPath);
                    if (serviceAccountStream == null) {
                        throw new FileNotFoundException("Google credentials.json not found on file system or as a classpath resource at: " + serviceAccountKeyPath);
                    }
                }
            } else {
                throw new IOException("Google service account credentials not found. Neither environment variable GOOGLE_APPLICATION_CREDENTIALS_JSON_STRING nor file path google.drive.service-account-key-path is configured.");
            }

            // Ensure stream was obtained successfully
            if (serviceAccountStream == null) {
                 throw new IOException("Failed to obtain InputStream for Google credentials.json after all attempts.");
            }

            GoogleCredentials credentials = GoogleCredentials.fromStream(serviceAccountStream)
                    .createScoped(Collections.singleton(DriveScopes.DRIVE));

            HttpRequestInitializer requestInitializer = new HttpRequestInitializer() {
                final HttpRequestInitializer originalInitializer = new HttpCredentialsAdapter(credentials);

                @Override
                public void initialize(HttpRequest httpRequest) throws IOException {
                    originalInitializer.initialize(httpRequest);
                    httpRequest.setConnectTimeout(60000);
                    httpRequest.setReadTimeout(60000);
                }
            };

            driveService = new Drive.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    JSON_FACTORY,
                    requestInitializer)
                    .setApplicationName(applicationName)
                    .build();

            System.out.println("GoogleDriveService initialized successfully.");

        } catch (FileNotFoundException e) {
            System.err.println("ERROR: Google credentials file not found: " + e.getMessage());
            throw e; // Re-throw to indicate critical failure
        } catch (IOException | GeneralSecurityException e) {
            System.err.println("ERROR: Failed to initialize GoogleDriveService: " + e.getMessage());
            throw e; // Re-throw to indicate critical failure
        } finally {
            if (serviceAccountStream != null) {
                try {
                    serviceAccountStream.close();
                } catch (IOException e) {
                    System.err.println("Error closing service account stream: " + e.getMessage());
                }
            }
        }
    }

    // ... (rest of your GoogleDriveService methods remain the same) ...
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