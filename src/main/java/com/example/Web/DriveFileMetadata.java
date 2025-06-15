package com.example.Web;




import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object (DTO) to encapsulate metadata of a Google Drive file.
 * Used for clearer API responses and data handling within the service.
 *
 * Requires Lombok dependency for @Data, @NoArgsConstructor, @AllArgsConstructor annotations.
 */
@Data // Generates getters, setters, toString(), equals(), and hashCode() methods automatically.
@NoArgsConstructor // Generates a constructor with no arguments.
// @AllArgsConstructor // Lombok's AllArgsConstructor - providing explicit one below as fallback
public class DriveFileMetadata {
    private String id; // Google Drive file ID
    private String name; // File name
    private String mimeType; // MIME type of the file (e.g., "text/csv", "application/vnd.google-apps.folder")
    private Long size; // Size of the file in bytes
    private String webContentLink; // A link to the file that you can embed in a webpage (might require authentication)
    private String webViewLink;    // A link for opening the file in a browser (e.g., Google Docs viewer)
    private Long createdTime; // Timestamp when the file was created (Unix epoch milliseconds)
    private Long modifiedTime; // Timestamp when the file was last modified (Unix epoch milliseconds)
    
    // Explicit constructor to ensure resolution.
    // This provides a fallback if @AllArgsConstructor isn't processed correctly by some IDE/setup.
    public DriveFileMetadata(String id, String name, String mimeType, Long size,
                             String webContentLink, String webViewLink, Long createdTime, Long modifiedTime) {
        this.id = id;
        this.name = name;
        this.mimeType = mimeType;
        this.size = size;
        this.webContentLink = webContentLink;
        this.webViewLink = webViewLink;
        this.createdTime = createdTime;
        this.modifiedTime = modifiedTime;
    }
}
