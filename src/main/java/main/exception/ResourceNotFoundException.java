package main.exception;

public class ResourceNotFoundException extends RuntimeException {
    
    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException forUser(String id) {
        return new ResourceNotFoundException("User not found: " + id);
    }

    public static ResourceNotFoundException forTransaction(String id) {
        return new ResourceNotFoundException("Transaction not found: " + id);
    }

    public static ResourceNotFoundException forAlert(String id) {
        return new ResourceNotFoundException("Fraud alert not found: " + id);
    }

}
