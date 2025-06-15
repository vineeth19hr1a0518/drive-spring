package com.example.Web;



import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuration class for handling Cross-Origin Resource Sharing (CORS).
 * This is crucial when your frontend is served from a different domain/port
 * than your backend API, or for allowing requests from your deployed frontend.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Configures CORS rules for your API endpoints.
     * @param registry The CorsRegistry to add CORS mappings to.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**") // Applies CORS to all API endpoints starting with /api
                // IMPORTANT: Replace with the actual URL(s) where your frontend will be hosted.
                // For local development, 'http://localhost:8080' is often the source if Spring Boot serves it.
                // For Render deployment, it might be the Render app URL itself (e.g., https://your-app-name.onrender.com)
                // or if you use a custom domain for frontend.
                // You can add multiple allowed origins separated by commas.
                .allowedOrigins(
                        "http://localhost:8080", // Allow requests from your local development server
                        "https://your-render-app-name.onrender.com" // Replace with your actual Render URL
                        // "https://your-custom-frontend-domain.com" // Add if you use a custom domain for frontend
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // Allowed HTTP methods
                .allowedHeaders("*") // Allow all headers
                .allowCredentials(true); // Allow sending credentials (cookies, HTTP authentication)
    }
}
