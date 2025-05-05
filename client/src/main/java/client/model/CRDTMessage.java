package client.model;


import java.util.List;

public class CRDTMessage {
    private String type; // "insert", "delete", or "batch"
    private CRDTCharacter character;
    private List<CRDTMessage> batchOperations; // For batch operations like paste

    public CRDTMessage() {}

    public CRDTMessage(String type, CRDTCharacter character) {
        this.type = type;
        this.character = character;
    }

    public String getType() { 
        return type; 
    }
    
    public CRDTCharacter getCharacter() { 
        return character; 
    }
    
    public List<CRDTMessage> getBatchOperations() {
        return batchOperations;
    }

    public void setType(String type) { 
        this.type = type; 
    }
    
    public void setCharacter(CRDTCharacter character) { 
        this.character = character; 
    }
    
    public void setBatchOperations(List<CRDTMessage> batchOperations) {
        this.batchOperations = batchOperations;
    }
        // Helper method to create a batch message
        public static CRDTMessage createBatchMessage(List<CRDTMessage> operations) {
            CRDTMessage batch = new CRDTMessage("batch", null);
            batch.setBatchOperations(operations);
            return batch;
        }
}