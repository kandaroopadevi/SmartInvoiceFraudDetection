package com.invoice;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity public class AuditLog {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private String action;
    @Column(length=1000) private String details;
    private LocalDateTime createdAt=LocalDateTime.now();
    protected AuditLog(){}
    public AuditLog(String action,String details){this.action=action;this.details=details;}
    public String getAction(){return action;} public String getDetails(){return details;} public LocalDateTime getCreatedAt(){return createdAt;}
}
