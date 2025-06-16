package com.example.Web;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File; // This is Google Drive's File
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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
// import java.io.File; // REMOVE THIS import, we will use the fully qualified name

@Service
public class GoogleDriveService {

    @Value("${GOOGLE_APPLICATION_CREDENTIALS_JSON_STRING:}")
    private String serviceAccountKeyJsonString;

    @Value("${google.drive.service-account-key-path:/etc/secrets/google-credentials.json}")
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
                System.out.println("Attempting to load Google credentials from environment variable GOOGLE_APPLICATION_CREDENTIALS_JSON_STRING.");
                serviceAccountStream = new ByteArrayInputStream(serviceAccountKeyJsonString.getBytes(StandardCharsets.UTF_8));
            } else if (serviceAccountKeyPath != null && !serviceAccountKeyPath.isEmpty()) {
                // Use fully qualified name for java.io.File
                java.io.File credentialsFile = new java.io.File(serviceAccountKeyPath); 

                if (credentialsFile.exists() && credentialsFile.isFile()) {
                    System.out.println("Attempting to load Google credentials from file system path: " + serviceAccountKeyPath);
                    serviceAccountStream = new FileInputStream(credentialsFile);
                } else {
                    System.err.println("Google credentials file NOT found at file system path: " + serviceAccountKeyPath);
                    System.err.println("Attempting to load as a Class Path Resource (fallback for local dev if file is bundled in JAR)...");
                    serviceAccountStream = getClass().getClassLoader().getResourceAsStream(serviceAccountKeyPath);
                    if (serviceAccountStream == null) {
                        throw new FileNotFoundException("Google credentials.json not found on file system or as a classpath resource at: " + serviceAccountKeyPath);
                    }
                }
            } else {
                throw new IOException("Google service account credentials not found. Neither environment variable GOOGLE_APPLICATION_CREDENTIALS_JSON_STRING nor file path google.drive.service-account-key-path is configured.");
            }

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
            throw e;
        } catch (IOException | GeneralSecurityException e) {
            System.err.println("ERROR: Failed to initialize GoogleDriveService: " + e.getMessage());
            throw e;
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

    public String uploadSalesDataFile(
            MultipartFile file,
            String monthNumber,
            String market,
            String country,
            String brand,
            String fromDate,
            String toDate) throws IOException {

        if (!"text/csv".equals(file.getContentType()) && !file.getOriginalFilename().toLowerCase().endsWith(".csv")) {
            throw new IOException("Only CSV files are allowed.");
        }

        String monthName = Month.of(Integer.parseInt(monthNumber)).getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);

        String monthFolderId = findOrCreateFolder(rootFolderId, monthName);

        String marketFolderId = findOrCreateFolder(monthFolderId, market);

        String countryFolderId = findOrCreateFolder(marketFolderId, country);

        String newFileName = String.format("%s-%s_%s.csv", brand, fromDate, toDate);

        InputStreamContent mediaContent = new InputStreamContent(file.getContentType(), file.getInputStream());

        File fileMetadata = new File(); // This refers to com.google.api.services.drive.model.File
        fileMetadata.setName(newFileName);
        fileMetadata.setMimeType(file.getContentType());
        fileMetadata.setParents(Collections.singletonList(countryFolderId));

        File uploadedFile = driveService.files().create(fileMetadata, mediaContent) // This refers to com.google.api.services.drive.model.File
                .setFields("id, name, webContentLink, webViewLink, mimeType, size")
                .execute();

        return uploadedFile.getId();
    }

    private String findOrCreateFolder(String parentId, String folderName) throws IOException {
        String query = String.format("name = '%s' and '%s' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false", folderName, parentId);

        FileList result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute();

        List<File> files = result.getFiles(); // This refers to com.google.api.services.drive.model.File

        if (files != null && !files.isEmpty()) {
            return files.get(0).getId();
        } else {
            File fileMetadata = new File(); // This refers to com.google.api.services.drive.model.File
            fileMetadata.setName(folderName);
            fileMetadata.setMimeType("application/vnd.google-apps.folder");
            fileMetadata.setParents(Collections.singletonList(parentId));

            File createdFolder = driveService.files().create(fileMetadata) // This refers to com.google.api.services.drive.model.File
                    .setFields("id, name")
                    .execute();
            return createdFolder.getId();
        }
    }

    public byte[] downloadFile(String fileId) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        driveService.files().get(fileId).executeMediaAndDownloadTo(outputStream);
        return outputStream.toByteArray();
    }

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
                        file.getCreatedTime() != null ? file.getCreatedTime().getValue() : null,
                        file.getModifiedTime() != null ? file.getModifiedTime().getValue() : null
                ))
                .collect(Collectors.toList());
    }

    public void renameFile(String fileId, String newName) throws IOException {
        File fileMetadata = new File(); // This refers to com.google.api.services.drive.model.File
        fileMetadata.setName(newName);
        driveService.files().update(fileId, fileMetadata).execute();
    }

    public void deleteFile(String fileId) throws IOException {
        driveService.files().delete(fileId).execute();
    }
}