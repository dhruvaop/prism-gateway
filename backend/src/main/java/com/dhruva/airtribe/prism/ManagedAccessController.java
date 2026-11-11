package com.dhruva.airtribe.prism;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class ManagedAccessController {
    private final ManagedAccessService managed;
    private final PasswordResetService resets;
    public ManagedAccessController(ManagedAccessService managed,PasswordResetService resets){this.managed=managed;this.resets=resets;}

    @GetMapping("/admin/plans") public Map<String,Object> plans(){return Map.of("plans",managed.plans());}
    @PostMapping("/admin/plans") public ResponseEntity<?> createPlan(@RequestBody Map<String,Object> body){try{return ResponseEntity.status(201).body(Map.of("plan",managed.createPlan(body)));}catch(IllegalArgumentException ex){return ResponseEntity.badRequest().body(error(ex.getMessage()));}}
    @PatchMapping("/admin/plans/{id}") public ResponseEntity<?> updatePlan(@PathVariable long id,@RequestBody Map<String,Object> body){try{return ResponseEntity.ok(Map.of("plan",managed.updatePlan(id,body)));}catch(IllegalArgumentException ex){return ResponseEntity.badRequest().body(error(ex.getMessage()));}}
    @DeleteMapping("/admin/plans/{id}") public ResponseEntity<?> deletePlan(@PathVariable long id){try{managed.deletePlan(id);return ResponseEntity.ok(Map.of("deleted",true));}catch(IllegalArgumentException ex){return ResponseEntity.badRequest().body(error(ex.getMessage()));}}
    @GetMapping("/admin/teams") public Map<String,Object> teams(){return Map.of("teams",managed.teams());}
    @PostMapping("/admin/teams") public ResponseEntity<?> createTeam(@RequestBody Map<String,Object> body){try{return ResponseEntity.status(201).body(Map.of("team",managed.createTeam(body)));}catch(IllegalArgumentException ex){return ResponseEntity.badRequest().body(error(ex.getMessage()));}}
    @PatchMapping("/admin/teams/{id}") public ResponseEntity<?> updateTeam(@PathVariable long id,@RequestBody Map<String,Object> body){try{return ResponseEntity.ok(Map.of("team",managed.updateTeam(id,body)));}catch(IllegalArgumentException ex){return ResponseEntity.badRequest().body(error(ex.getMessage()));}}
    @DeleteMapping("/admin/teams/{id}") public ResponseEntity<?> deleteTeam(@PathVariable long id){try{managed.deleteTeam(id);return ResponseEntity.ok(Map.of("deleted",true));}catch(IllegalArgumentException ex){return ResponseEntity.badRequest().body(error(ex.getMessage()));}}
    @GetMapping("/admin/users") public Map<String,Object> users(){return Map.of("users",managed.users());}
    @GetMapping("/admin/metrics") public Map<String,Object> metrics(){return managed.metrics();}
    @GetMapping("/admin/billing/transactions") public Map<String,Object> billingTransactions(@RequestParam(defaultValue="100") int limit){return Map.of("transactions",managed.billingTransactions(limit));}
    @GetMapping("/admin/customer-usage") public Map<String,Object> customerUsage(@RequestParam(defaultValue="100") int limit){return Map.of("users",managed.customerUsage(limit));}
    @PostMapping("/admin/users") public ResponseEntity<?> createUser(@RequestBody Map<String,Object> body){try{return ResponseEntity.status(201).body(Map.of("user",managed.createUser(body)));}catch(IllegalArgumentException ex){return ResponseEntity.badRequest().body(error(ex.getMessage()));}}
    @PatchMapping("/admin/users/{id}") public ResponseEntity<?> updateUser(@PathVariable long id,@RequestBody Map<String,Object> body){try{return ResponseEntity.ok(Map.of("user",managed.updateUser(id,body)));}catch(IllegalArgumentException ex){return ResponseEntity.badRequest().body(error(ex.getMessage()));}}
    @PostMapping("/admin/users/{id}/password-reset") public ResponseEntity<?> resetUserPassword(@PathVariable long id){
        try{
            PasswordResetService.ResetDelivery delivery=resets.requestChatResetForUser(id);
            Map<String,Object> response=new java.util.LinkedHashMap<>();response.put("message",delivery.message());response.put("delivery",delivery.delivery());
            if(delivery.resetLink()!=null)response.put("reset_link",delivery.resetLink());
            return ResponseEntity.ok(response);
        }catch(IllegalArgumentException ex){return ResponseEntity.badRequest().body(error(ex.getMessage()));}
    }
    @DeleteMapping("/admin/users/{id}") public ResponseEntity<?> disableUser(@PathVariable long id){try{return ResponseEntity.ok(Map.of("user",managed.updateUser(id,Map.of("enabled",false))));}catch(IllegalArgumentException ex){return ResponseEntity.badRequest().body(error(ex.getMessage()));}}
    @GetMapping("/admin/managed-keys/ref/{ref}") public ResponseEntity<?> managedKey(@PathVariable String ref){try{return ResponseEntity.ok(Map.of("key",managed.managedKey(managed.resolveKeyRef(ref))));}catch(AuthService.AuthException ex){return ResponseEntity.status(ex.status()).body(error(ex.getMessage()));}}
    @PatchMapping("/admin/managed-keys/ref/{ref}") public ResponseEntity<?> updateManagedKey(@PathVariable String ref,@RequestBody Map<String,Object> body){
        try{String profile=String.valueOf(body.getOrDefault("routing_profile",""));return ResponseEntity.ok(Map.of("key",managed.setKeyRoutingProfile(managed.resolveKeyRef(ref),profile)));}
        catch(AuthService.AuthException ex){return ResponseEntity.status(ex.status()).body(error(ex.getMessage()));}
        catch(PrismService.GatewayException ex){return ResponseEntity.status(ex.status).body(error(ex.getMessage()));}
    }
    @PostMapping("/admin/managed-keys/ref/{ref}/disable") public ResponseEntity<?> disable(@PathVariable String ref){try{managed.setKeyEnabled(managed.resolveKeyRef(ref),false);return ResponseEntity.ok(Map.of("ok",true));}catch(AuthService.AuthException ex){return ResponseEntity.status(ex.status()).body(error(ex.getMessage()));}}
    @PostMapping("/admin/managed-keys/ref/{ref}/enable") public ResponseEntity<?> enable(@PathVariable String ref){try{managed.setKeyEnabled(managed.resolveKeyRef(ref),true);return ResponseEntity.ok(Map.of("ok",true));}catch(AuthService.AuthException ex){return ResponseEntity.status(ex.status()).body(error(ex.getMessage()));}}
    @PostMapping("/admin/managed-keys/ref/{ref}/rotate") public ResponseEntity<?> rotate(@PathVariable String ref){try{return ResponseEntity.ok(managed.rotateKey(managed.resolveKeyRef(ref)));}catch(AuthService.AuthException ex){return ResponseEntity.status(ex.status()).body(error(ex.getMessage()));}}
    private Map<String,Object> error(String message){return Map.of("error",Map.of("message",message));}
}
