package com.dhruva.airtribe.prism;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class ManagedAccessService {
    private final JdbcTemplate db;
    private final PrismService prism;
    private final AuthService auth;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final int trialRequests;

    public ManagedAccessService(JdbcTemplate db, PrismService prism, AuthService auth,
                                @Value("${prism.billing.trial-requests:5}") int trialRequests) {
        this.db = db;
        this.prism = prism;
        this.auth = auth;
        this.trialRequests = Math.max(1, trialRequests);
    }

    public List<Map<String,Object>> plans() {
        return db.queryForList("SELECT id,name,monthly_budget_usd,requests_per_minute,tokens_per_minute,token_cap,model_allowlist,verify_models,cache_enabled,similarity_threshold,cache_ttl_seconds,guardrail_injection,guardrail_pii FROM plans ORDER BY id");
    }

    public Map<String,Object> createPlan(Map<String,Object> body) {
        String name = text(body,"name");
        List<String> allowlist = strings(body.get("model_allowlist"));
        if (name.isBlank() || allowlist.isEmpty() || allowlist.stream().anyMatch(x -> !Set.of("auto","fast","smart").contains(x))) throw bad("A plan name and one or more of auto, fast, or smart are required.");
        double budget = number(body,"monthly_budget_usd",50);
        int rpm = (int)number(body,"rpm_limit",60);
        int tpm = (int)number(body,"tpm_limit",0);
        long tokenCap = (long)number(body,"token_cap",0);
        if (budget < 0 || rpm < 1 || tpm < 0 || tokenCap < 0) throw bad("Budget and token limits must be non-negative; RPM must be at least 1.");
        List<String> verify = strings(body.get("verify_models"));
        verify.retainAll(allowlist);
        String injection = policy(body,"guardrail_injection","off"), pii = policy(body,"guardrail_pii","off");
        Long ttl = body.get("cache_ttl_seconds") == null ? null : (long)number(body,"cache_ttl_seconds",0);
        try {
            db.update("INSERT INTO plans(name,monthly_budget_usd,requests_per_minute,tokens_per_minute,token_cap,model_allowlist,verify_models,cache_enabled,similarity_threshold,cache_ttl_seconds,guardrail_injection,guardrail_pii) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    name,budget,rpm,tpm==0?null:tpm,tokenCap==0?null:tokenCap,json.writeValueAsString(allowlist),json.writeValueAsString(verify),bool(body,"cache_enabled",true)?1:0,number(body,"cache_similarity",0.92),ttl,injection,pii);
        } catch (Exception ex) { throw bad("Could not create plan: "+ex.getMessage()); }
        return db.queryForMap("SELECT * FROM plans WHERE name=?",name);
    }

    @Transactional public Map<String,Object> updatePlan(long id,Map<String,Object> body){
        Map<String,Object> old;try{old=db.queryForMap("SELECT * FROM plans WHERE id=?",id);}catch(Exception ex){throw bad("Plan not found.");}
        String name=body.containsKey("name")?text(body,"name"):String.valueOf(old.get("name"));
        List<String> allowlist=body.containsKey("model_allowlist")?strings(body.get("model_allowlist")):readStrings(old.get("model_allowlist"));
        if(name.isBlank()||allowlist.isEmpty()||allowlist.stream().anyMatch(x->!Set.of("auto","fast","smart").contains(x)))throw bad("A plan name and one or more of auto, fast, or smart are required.");
        List<String> verify=body.containsKey("verify_models")?strings(body.get("verify_models")):readStrings(old.get("verify_models"));verify.retainAll(allowlist);
        double budget=body.containsKey("monthly_budget_usd")?number(body,"monthly_budget_usd",50):((Number)old.get("monthly_budget_usd")).doubleValue();
        int rpm=body.containsKey("rpm_limit")?(int)number(body,"rpm_limit",60):((Number)old.get("requests_per_minute")).intValue();
        int tpm=body.containsKey("tpm_limit")?(int)number(body,"tpm_limit",0):old.get("tokens_per_minute")==null?0:((Number)old.get("tokens_per_minute")).intValue();
        long cap=body.containsKey("token_cap")?(long)number(body,"token_cap",0):old.get("token_cap")==null?0:((Number)old.get("token_cap")).longValue();
        if(budget<0||rpm<1||tpm<0||cap<0)throw bad("Budget and token limits must be non-negative; RPM must be at least 1.");
        String injection=body.containsKey("guardrail_injection")?policy(body,"guardrail_injection","off"):String.valueOf(old.get("guardrail_injection"));
        String pii=body.containsKey("guardrail_pii")?policy(body,"guardrail_pii","off"):String.valueOf(old.get("guardrail_pii"));
        Long ttl=body.containsKey("cache_ttl_seconds")?(long)number(body,"cache_ttl_seconds",0):old.get("cache_ttl_seconds")==null?null:((Number)old.get("cache_ttl_seconds")).longValue();
        try{db.update("UPDATE plans SET name=?,monthly_budget_usd=?,requests_per_minute=?,tokens_per_minute=?,token_cap=?,model_allowlist=?,verify_models=?,cache_enabled=?,similarity_threshold=?,cache_ttl_seconds=?,guardrail_injection=?,guardrail_pii=? WHERE id=?",name,budget,rpm,tpm==0?null:tpm,cap==0?null:cap,json.writeValueAsString(allowlist),json.writeValueAsString(verify),body.containsKey("cache_enabled")?(bool(body,"cache_enabled",true)?1:0):old.get("cache_enabled"),body.containsKey("cache_similarity")?number(body,"cache_similarity",.92):old.get("similarity_threshold"),ttl,injection,pii,id);}catch(Exception ex){throw bad("Could not update plan: "+ex.getMessage());}
        List<Long> members=db.queryForList("SELECT u.id FROM access_users u JOIN teams t ON t.id=u.team_id WHERE t.plan_id=?",Long.class,id);for(Long member:members)updateUser(member,Map.of());
        return db.queryForMap("SELECT * FROM plans WHERE id=?",id);
    }
    public void deletePlan(long id){try{if(db.queryForObject("SELECT COUNT(*) FROM teams WHERE plan_id=?",Integer.class,id)>0)throw bad("Move all teams to another plan before deleting this plan.");if(db.update("DELETE FROM plans WHERE id=?",id)==0)throw bad("Plan not found.");}catch(IllegalArgumentException ex){throw ex;}catch(Exception ex){throw bad("Could not delete plan: "+ex.getMessage());}}

    public List<Map<String,Object>> teams() {
        return db.queryForList("SELECT t.id,t.name,t.plan_id,p.name AS plan_name FROM teams t JOIN plans p ON p.id=t.plan_id ORDER BY t.id");
    }

    public Map<String,Object> createTeam(Map<String,Object> body) {
        String name=text(body,"name"); long planId=(long)number(body,"plan_id",0);
        if(name.isBlank()||planId<1) throw bad("Team name and a valid plan_id are required.");
        try { db.update("INSERT INTO teams(name,plan_id) VALUES(?,?)",name,planId); }
        catch(Exception ex){throw bad("Could not create team: "+ex.getMessage());}
        return db.queryForMap("SELECT t.id,t.name,t.plan_id,p.name AS plan_name FROM teams t JOIN plans p ON p.id=t.plan_id WHERE t.name=?",name);
    }
    public Map<String,Object> updateTeam(long id,Map<String,Object> body){String name=text(body,"name").trim();long planId=(long)number(body,"plan_id",0);if(name.isBlank()||planId<1)throw bad("Team name and a valid plan_id are required.");try{db.update("UPDATE teams SET name=?,plan_id=? WHERE id=?",name,planId,id);List<Long> members=db.queryForList("SELECT id FROM access_users WHERE team_id=?",Long.class,id);for(Long member:members)updateUser(member,Map.of("team",name));return db.queryForMap("SELECT t.id,t.name,t.plan_id,p.name AS plan_name FROM teams t JOIN plans p ON p.id=t.plan_id WHERE t.id=?",id);}catch(Exception ex){throw bad("Could not update team: "+ex.getMessage());}}
    public void deleteTeam(long id){try{if(db.queryForObject("SELECT COUNT(*) FROM access_users WHERE team_id=?",Integer.class,id)>0)throw bad("Move or disable this team's users before deleting it.");if(db.update("DELETE FROM teams WHERE id=?",id)==0)throw bad("Team not found.");}catch(IllegalArgumentException ex){throw ex;}catch(Exception ex){throw bad("Could not delete team: "+ex.getMessage());}}

    public List<Map<String,Object>> users() {
        List<Map<String,Object>> rows=db.queryForList("SELECT u.id,u.name,u.username,u.email,u.team_id,t.name AS team,u.access_mode,u.enabled,u.created_at,u.trial_requests_used,u.virtual_key,(SELECT s.plan FROM subscriptions s WHERE s.user_id=u.id AND s.status='active' AND s.ends_at>? ORDER BY s.ends_at DESC LIMIT 1) AS subscription_plan FROM access_users u JOIN teams t ON t.id=u.team_id ORDER BY u.id",Instant.now().toString());
        List<Map<String,Object>> out=new ArrayList<>(rows.size());
        for(Map<String,Object> row:rows)out.add(keySafeUser(row));
        return out;
    }
    public List<Map<String,Object>> keys(){List<Map<String,Object>> out=db.queryForList("SELECT virtual_key,team,monthly_budget_usd AS budget_usd,requests_per_minute AS rpm_limit,tokens_per_minute AS tpm_limit,model_allowlist AS allowlist,cache_enabled,similarity_threshold,enabled,user_id,routing_profile FROM tenant_keys ORDER BY team,virtual_key");for(Map<String,Object> row:out){String key=String.valueOf(row.remove("virtual_key"));row.put("key_ref",keyRef(key));row.put("key_hint",keyHint(key));row.put("allowlist",readStrings(row.get("allowlist")));row.put("routing_profile",prism.cleanRoutingProfile(String.valueOf(row.get("routing_profile"))));}return out;}

    /**
     * Opaque, stable handle for a virtual key. The browser never needs the
     * actual secret to select or operate a key from the admin console.
     */
    static String keyRef(String key) {
        if (key == null || key.isBlank()) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return "key_" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to derive the key reference.", ex);
        }
    }

    static String keyHint(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 16) return key;
        return key.substring(0, 12) + "…" + key.substring(key.length() - 4);
    }

    /** Resolves a browser-supplied key_ref back to the server-side secret. */
    public String resolveKeyRef(String ref) {
        String clean = ref == null ? "" : ref.trim();
        if (clean.isBlank()) throw new AuthService.AuthException(400, "Choose a virtual key.");
        for (String key : db.queryForList("SELECT virtual_key FROM tenant_keys", String.class)) {
            if (clean.equals(keyRef(key))) return key;
        }
        throw new AuthService.AuthException(404, "Virtual key not found.");
    }

    @Transactional
    public Map<String,Object> createUser(Map<String,Object> body) {
        String name=text(body,"name").trim(), username=text(body,"username").trim().toLowerCase(Locale.ROOT), team=text(body,"team").trim(), password=text(body,"password");
        String email=text(body,"email").trim().toLowerCase(Locale.ROOT);
        String access=text(body,"access_mode"); if(access.isBlank())access="both";
        if(name.length()<2||!username.matches("[a-z0-9._-]{3,32}")||password.length()<8||password.length()>72||!Set.of("api","chat","both").contains(access))throw bad("Provide a name, a 3–32 character username, a password of 8–72 characters, and access_mode api, chat, or both.");
        if(!email.isEmpty()&&!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))throw bad("Enter a valid email address.");
        if(email.isEmpty()&&(access.equals("chat")||access.equals("both")))throw bad("An email address is required for chat accounts so users can reset their password.");
        Map<String,Object> selectedPlan;
        try { selectedPlan=db.queryForMap("SELECT p.* FROM teams t JOIN plans p ON p.id=t.plan_id WHERE t.name=?",team); }
        catch(Exception ex){throw bad("Choose an existing team.");}
        String key="prism-sk-"+randomHex(24), now=Instant.now().toString();
        try {
            db.update("INSERT INTO access_users(name,username,email,password_hash,team_id,access_mode,created_at) SELECT ?,?,?,?,id,?,? FROM teams WHERE name=?",name,username,email.isEmpty()?null:email,passwords.encode(password),access,now,team);
            long userId=db.queryForObject("SELECT id FROM access_users WHERE username=?",Long.class,username);
            List<String> allowlist=readStrings(selectedPlan.get("model_allowlist"));
            String encodedAllow=json.writeValueAsString(allowlist);
            db.update("INSERT INTO tenant_keys(virtual_key,team,monthly_budget_usd,requests_per_minute,tokens_per_minute,model_allowlist,cache_enabled,similarity_threshold,enabled,user_id,token_cap,cache_ttl_seconds,guardrail_injection,guardrail_pii,verify_models,routing_profile) VALUES(?,?,?,?,?,?,?,?,1,?,?,?,?,?,?,?)",
                    key,team,selectedPlan.get("monthly_budget_usd"),selectedPlan.get("requests_per_minute"),selectedPlan.get("tokens_per_minute"),encodedAllow,selectedPlan.get("cache_enabled"),selectedPlan.get("similarity_threshold"),userId,selectedPlan.get("token_cap"),selectedPlan.get("cache_ttl_seconds"),selectedPlan.get("guardrail_injection"),selectedPlan.get("guardrail_pii"),selectedPlan.get("verify_models"),"auto");
            db.update("UPDATE access_users SET virtual_key=? WHERE id=?",key,userId);
            prism.refreshManagedTenant(key);
            return Map.of("user_id",userId,"name",name,"username",username,"email",email,"team",team,"access_mode",access,"virtual_key",key,"created_at",now);
        } catch(Exception ex){throw bad("Could not create user: "+ex.getMessage());}
    }

    @Transactional public Map<String,Object> updateUser(long id,Map<String,Object> body){
        Map<String,Object> old;try{old=db.queryForMap("SELECT * FROM access_users WHERE id=?",id);}catch(Exception ex){throw bad("User not found.");}
        String name=body.containsKey("name")?text(body,"name").trim():String.valueOf(old.get("name"));
        String username=body.containsKey("username")?text(body,"username").trim().toLowerCase(Locale.ROOT):String.valueOf(old.get("username"));
        String access=body.containsKey("access_mode")?text(body,"access_mode"):String.valueOf(old.get("access_mode"));
        String email=body.containsKey("email")?text(body,"email").trim().toLowerCase(Locale.ROOT):(old.get("email")==null?"":String.valueOf(old.get("email")));
        String team=body.containsKey("team")?text(body,"team").trim():String.valueOf(db.queryForObject("SELECT name FROM teams WHERE id=?",String.class,old.get("team_id")));
        String password=text(body,"password");
        if(name.length()<2||!username.matches("[a-z0-9._-]{3,32}")||!Set.of("api","chat","both").contains(access)||(!password.isEmpty()&&(password.length()<8||password.length()>72)))throw bad("Invalid name, username, password, or access mode. Passwords must be 8–72 characters.");
        if(!email.isEmpty()&&!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))throw bad("Enter a valid email address.");
        if(email.isEmpty()&&(access.equals("chat")||access.equals("both")))throw bad("An email address is required for chat accounts so users can reset their password.");
        Map<String,Object> plan;try{plan=db.queryForMap("SELECT p.* FROM teams t JOIN plans p ON p.id=t.plan_id WHERE t.name=?",team);}catch(Exception ex){throw bad("Choose an existing team.");}
        String key=(String)old.get("virtual_key");boolean enabled=body.containsKey("enabled")?Boolean.parseBoolean(String.valueOf(body.get("enabled"))):((Number)old.get("enabled")).intValue()==1;
        try{db.update("UPDATE access_users SET name=?,username=?,email=?,team_id=(SELECT id FROM teams WHERE name=?),access_mode=?,password_hash=?,enabled=? WHERE id=?",name,username,email.isEmpty()?null:email,team,access,password.isEmpty()?old.get("password_hash"):passwords.encode(password),enabled?1:0,id);
            if(key!=null){db.update("UPDATE tenant_keys SET team=?,monthly_budget_usd=?,requests_per_minute=?,tokens_per_minute=?,model_allowlist=?,cache_enabled=?,similarity_threshold=?,user_id=?,token_cap=?,cache_ttl_seconds=?,guardrail_injection=?,guardrail_pii=?,verify_models=?,enabled=? WHERE virtual_key=?",team,plan.get("monthly_budget_usd"),plan.get("requests_per_minute"),plan.get("tokens_per_minute"),plan.get("model_allowlist"),plan.get("cache_enabled"),plan.get("similarity_threshold"),id,plan.get("token_cap"),plan.get("cache_ttl_seconds"),plan.get("guardrail_injection"),plan.get("guardrail_pii"),plan.get("verify_models"),enabled?1:0,key);if(enabled)prism.refreshManagedTenant(key);else prism.removeManagedTenant(key);}
            return keySafeUser(db.queryForMap("SELECT id,name,username,email,team_id,access_mode,enabled,virtual_key,created_at FROM access_users WHERE id=?",id));
        }catch(Exception ex){throw bad("Could not update user: "+ex.getMessage());}
    }

    /** Removes the raw key from admin user payloads and replaces it with refs. */
    private Map<String,Object> keySafeUser(Map<String,Object> row){
        Map<String,Object> out=new LinkedHashMap<>(row);
        Object key=out.remove("virtual_key");
        if(key!=null&&!String.valueOf(key).isBlank()){
            out.put("key_ref",keyRef(String.valueOf(key)));
            out.put("key_hint",keyHint(String.valueOf(key)));
        }
        return out;
    }

    /**
     * Public self-serve signup for the chat app. Every new account starts on the
     * five-request demo; payment is handled separately by BillingService.
     */
    public Map<String,Object> selfSignup(Map<String,Object> body) {
        String name=text(body,"name").trim(),email=text(body,"email").trim().toLowerCase(Locale.ROOT),password=text(body,"password");
        if(name.length()<2||name.length()>100)throw bad("Enter a name between 2 and 100 characters.");
        if(!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")||email.length()>254)throw bad("Enter a valid email address.");
        if(password.length()<8||password.length()>72)throw bad("Password must be between 8 and 72 characters.");
        Integer existing=db.queryForObject("SELECT COUNT(*) FROM access_users WHERE lower(email)=?",Integer.class,email);
        if(existing!=null&&existing>0)throw new AuthService.AuthException(409,"An account with this email already exists. Sign in instead.");
        String base=email.substring(0,email.indexOf('@')).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]","").replaceAll("^[._-]+|[._-]+$","");
        if(base.length()<3)base="user";
        if(base.length()>28)base=base.substring(0,28);
        String username=base;int suffix=1;
        while(db.queryForObject("SELECT COUNT(*) FROM access_users WHERE username=?",Integer.class,username)>0){
            String tail=String.valueOf(++suffix);
            username=(base.length()+tail.length()>32?base.substring(0,32-tail.length()):base)+tail;
        }
        createUser(Map.of("name",name,"username",username,"email",email,"password",password,"team","Self-serve","access_mode","both"));
        Map<String,Object> account=db.queryForMap("SELECT id FROM access_users WHERE username=?",username);
        db.update("UPDATE access_users SET self_signup=1 WHERE id=?",account.get("id"));
        return login(email,password);
    }

    public Map<String,Object> login(String username,String password) {
        String identity=username==null?"":username.trim().toLowerCase(Locale.ROOT);
        List<Map<String,Object>> rows=db.queryForList("SELECT u.id,u.name,u.username,u.email,u.password_hash,u.access_mode,u.enabled,u.virtual_key,t.name AS team,p.model_allowlist,p.verify_models FROM access_users u JOIN teams t ON t.id=u.team_id JOIN plans p ON p.id=t.plan_id WHERE lower(u.username)=? OR lower(u.email)=? ORDER BY CASE WHEN lower(u.username)=? THEN 0 ELSE 1 END",identity,identity,identity);
        if(rows.isEmpty()||((Number)rows.get(0).get("enabled")).intValue()!=1||password==null||!passwords.matches(password,String.valueOf(rows.get(0).get("password_hash"))))throw new AuthService.AuthException(401,"Username or password is incorrect.");
        Map<String,Object> row=rows.get(0);long id=((Number)row.get("id")).longValue();String access=String.valueOf(row.get("access_mode"));if("api".equals(access))throw new AuthService.AuthException(403,"This account has API-only access.");
        String token=auth.issueChatToken(id,String.valueOf(row.get("username")),String.valueOf(row.get("name")),row.get("email")==null?null:String.valueOf(row.get("email")));
        Map<String,Object> user=new LinkedHashMap<>();user.put("id",id);user.put("name",row.get("name"));user.put("username",row.get("username"));user.put("email",row.get("email"));user.put("team",row.get("team"));user.put("access_mode",access);user.put("model_allowlist",readStrings(row.get("model_allowlist")));user.put("verify_models",row.get("verify_models"));
        return Map.of("token",token,"user",user);
    }

    public long requireChatUser(String bearer) {
        String token=bearer!=null&&bearer.startsWith("Bearer ")?bearer.substring(7).trim():"";
        String subject=auth.tokenSubject(token);
        if(subject==null)throw new AuthService.AuthException(401,"A valid chat sign-in is required.");
        try { long id=Long.parseLong(subject); Integer active=db.queryForObject("SELECT COUNT(*) FROM access_users WHERE id=? AND enabled=1 AND access_mode IN ('chat','both')",Integer.class,id);if(active==null||active==0)throw new NumberFormatException();return id; }
        catch(Exception ex){throw new AuthService.AuthException(401,"A valid chat sign-in is required.");}
    }

    public Map<String,Object> chatProfile(long userId){return db.queryForMap("SELECT u.id,u.name,u.username,u.email,u.access_mode,u.virtual_key,t.name AS team,p.model_allowlist,p.verify_models,p.token_cap FROM access_users u JOIN teams t ON t.id=u.team_id JOIN plans p ON p.id=t.plan_id WHERE u.id=? AND u.enabled=1",userId);}
    public Map<String,Object> chatUsage(long userId){Map<String,Object> p=chatProfile(userId);String key=String.valueOf(p.get("virtual_key")),month=java.time.YearMonth.now(java.time.ZoneOffset.UTC).toString();List<Map<String,Object>> rows=db.queryForList("SELECT prompt_tokens,completion_tokens,requests FROM usage_monthly WHERE virtual_key=? AND month=?",key,month);Map<String,Object> row=rows.isEmpty()?Map.of("prompt_tokens",0,"completion_tokens",0,"requests",0):rows.get(0);long total=((Number)row.get("prompt_tokens")).longValue()+((Number)row.get("completion_tokens")).longValue();return Map.of("month",month,"prompt_tokens",row.get("prompt_tokens"),"completion_tokens",row.get("completion_tokens"),"total_tokens",total,"requests",row.get("requests"),"token_cap",p.get("token_cap")==null?0:p.get("token_cap"));}
    public List<Map<String,Object>> conversations(long userId){return db.queryForList("SELECT id,title,created_at,updated_at FROM conversations WHERE user_id=? ORDER BY updated_at DESC",userId);}
    public long createConversation(long userId,String title){String now=Instant.now().toString(),clean=title==null||title.isBlank()?"New conversation":title.trim();db.update("INSERT INTO conversations(user_id,title,created_at,updated_at) VALUES(?,?,?,?)",userId,clean,now,now);return db.queryForObject("SELECT id FROM conversations WHERE user_id=? AND created_at=? ORDER BY id DESC",Long.class,userId,now);}
    public Map<String,Object> conversation(long userId,long id){try{Map<String,Object> row=db.queryForMap("SELECT id,title,created_at,updated_at FROM conversations WHERE id=? AND user_id=?",id,userId);row.put("messages",db.queryForList("SELECT role,content,created_at FROM conversation_messages WHERE conversation_id=? ORDER BY id",id));return row;}catch(Exception ex){throw new AuthService.AuthException(404,"Conversation not found.");}}
    public void renameConversation(long userId,long id,String title){if(title==null||title.isBlank())throw bad("Conversation title is required.");int changed=db.update("UPDATE conversations SET title=?,updated_at=? WHERE id=? AND user_id=?",title.trim(),Instant.now().toString(),id,userId);if(changed==0)throw new AuthService.AuthException(404,"Conversation not found.");}
    public void deleteConversation(long userId,long id){int changed=db.update("DELETE FROM conversations WHERE id=? AND user_id=?",id,userId);if(changed==0)throw new AuthService.AuthException(404,"Conversation not found.");}
    public void appendMessage(long userId,long conversationId,String role,String content){if(!Set.of("user","assistant").contains(role))return;String now=Instant.now().toString();int changed=db.update("INSERT INTO conversation_messages(conversation_id,role,content,created_at) SELECT ?,?,?,? WHERE EXISTS(SELECT 1 FROM conversations WHERE id=? AND user_id=?)",conversationId,role,content,now,conversationId,userId);if(changed>0)db.update("UPDATE conversations SET updated_at=? WHERE id=? AND user_id=?",now,conversationId,userId);}
    public Map<String,Object> managedKey(String key){try{Map<String,Object> row=db.queryForMap("SELECT team,monthly_budget_usd AS budget_usd,requests_per_minute AS rpm_limit,tokens_per_minute AS tpm_limit,model_allowlist AS allowlist,cache_enabled,similarity_threshold,token_cap,cache_ttl_seconds,guardrail_injection,guardrail_pii,enabled,routing_profile FROM tenant_keys WHERE virtual_key=?",key);row.put("key_ref",keyRef(key));row.put("key_hint",keyHint(key));row.put("routing_profile",prism.cleanRoutingProfile(String.valueOf(row.get("routing_profile"))));return row;}catch(Exception ex){throw new AuthService.AuthException(404,"Virtual key not found.");}}
    public void setKeyEnabled(String key,boolean enabled){assertManagedKey(key);int n=db.update("UPDATE tenant_keys SET enabled=? WHERE virtual_key=?",enabled?1:0,key);if(n==0)throw new AuthService.AuthException(404,"Virtual key not found.");if(enabled)prism.refreshManagedTenant(key);else prism.removeManagedTenant(key);}
    /**
     * Changes the routing middleware profile for any key (including seeded demo
     * keys). This is a policy change, not a secret operation, so it does not
     * require the key to belong to a managed user.
     */
    public Map<String,Object> setKeyRoutingProfile(String key,String profile){
        String normalized=prism.requireRoutingProfile(profile);
        int n=db.update("UPDATE tenant_keys SET routing_profile=? WHERE virtual_key=?",normalized,key);
        if(n==0)throw new AuthService.AuthException(404,"Virtual key not found.");
        Integer enabled=db.queryForObject("SELECT enabled FROM tenant_keys WHERE virtual_key=?",Integer.class,key);
        if(enabled!=null&&enabled!=0)prism.refreshManagedTenant(key);else prism.removeManagedTenant(key);
        return managedKey(key);
    }
    public Map<String,Object> rotateKey(String oldKey){assertManagedKey(oldKey);String newKey="prism-sk-"+randomHex(24);int n=db.update("UPDATE tenant_keys SET virtual_key=? WHERE virtual_key=?",newKey,oldKey);if(n==0)throw new AuthService.AuthException(404,"Virtual key not found.");db.update("UPDATE access_users SET virtual_key=? WHERE virtual_key=?",newKey,oldKey);prism.removeManagedTenant(oldKey);prism.refreshManagedTenant(newKey);return Map.of("key",newKey);}
    private void assertManagedKey(String key){try{Long owner=db.queryForObject("SELECT user_id FROM tenant_keys WHERE virtual_key=?",Long.class,key);if(owner==null)throw bad("Seeded demo keys cannot be changed here.");}catch(org.springframework.dao.EmptyResultDataAccessException ex){throw new AuthService.AuthException(404,"Virtual key not found.");}}

    /**
     * Admin-only product analytics: who signed up, who is active, what they
     * consume, and how subscriptions are performing. Never exposes secrets.
     */
    public Map<String,Object> metrics() {
        String now=Instant.now().toString();
        String today=LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
        String week=LocalDate.now(ZoneOffset.UTC).minusDays(6).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
        String month=YearMonth.now(ZoneOffset.UTC).toString();
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("total_users",count("SELECT COUNT(*) FROM access_users"));
        out.put("active_users",count("SELECT COUNT(*) FROM access_users WHERE enabled=1"));
        out.put("chat_users",count("SELECT COUNT(*) FROM access_users WHERE enabled=1 AND access_mode IN ('chat','both')"));
        out.put("api_users",count("SELECT COUNT(*) FROM access_users WHERE enabled=1 AND access_mode IN ('api','both')"));
        out.put("self_signup_users",count("SELECT COUNT(*) FROM access_users WHERE self_signup=1"));
        out.put("trial_limit",trialRequests);
        out.put("trial_users",count("SELECT COUNT(*) FROM access_users u WHERE u.self_signup=1 AND COALESCE(u.trial_requests_used,0)<? AND NOT EXISTS(SELECT 1 FROM subscriptions s WHERE s.user_id=u.id AND s.status='active' AND s.ends_at>?)",trialRequests,now));
        out.put("trial_exhausted_users",count("SELECT COUNT(*) FROM access_users u WHERE u.self_signup=1 AND COALESCE(u.trial_requests_used,0)>=? AND NOT EXISTS(SELECT 1 FROM subscriptions s WHERE s.user_id=u.id AND s.status='active' AND s.ends_at>?)",trialRequests,now));
        out.put("active_subscribers",count("SELECT COUNT(DISTINCT user_id) FROM subscriptions WHERE status='active' AND ends_at>?",now));
        out.put("mrr_paise",longTotal("SELECT COALESCE(SUM(amount_paise),0) FROM subscriptions WHERE status='active' AND ends_at>?",now));
        out.put("collected_paise",longTotal("SELECT COALESCE(SUM(amount_paise),0) FROM subscriptions WHERE razorpay_payment_id IS NOT NULL"));
        out.put("requests_total",count("SELECT COUNT(*) FROM request_logs"));
        out.put("requests_today",count("SELECT COUNT(*) FROM request_logs WHERE created_at>=?",today));
        out.put("active_users_today",count("SELECT COUNT(DISTINCT k.user_id) FROM request_logs l JOIN tenant_keys k ON k.virtual_key=l.virtual_key WHERE l.created_at>=? AND k.user_id IS NOT NULL",today));
        out.put("active_users_7d",count("SELECT COUNT(DISTINCT k.user_id) FROM request_logs l JOIN tenant_keys k ON k.virtual_key=l.virtual_key WHERE l.created_at>=? AND k.user_id IS NOT NULL",week));
        out.put("signups_today",count("SELECT COUNT(*) FROM access_users WHERE created_at>=?",today));
        out.put("signups_7d",count("SELECT COUNT(*) FROM access_users WHERE created_at>=?",week));
        out.put("tokens_this_month",longTotal("SELECT COALESCE(SUM(prompt_tokens+completion_tokens),0) FROM usage_monthly WHERE month=?",month));
        out.put("spend_this_month_usd",doubleTotal("SELECT COALESCE(SUM(cost_usd),0) FROM usage_monthly WHERE month=?",month));
        out.put("cache_hits_this_month",longTotal("SELECT COALESCE(SUM(cache_hits),0) FROM usage_monthly WHERE month=?",month));
        out.put("plan_breakdown",db.queryForList("SELECT plan,COUNT(*) AS subscribers,COALESCE(SUM(amount_paise),0) AS mrr_paise FROM subscriptions WHERE status='active' AND ends_at>? GROUP BY plan ORDER BY plan",now));
        return out;
    }

    /** Admin-only Razorpay subscription ledger. */
    public List<Map<String,Object>> billingTransactions(int limit) {
        return db.queryForList("SELECT s.id,s.user_id,u.name AS user_name,u.email AS user_email,s.plan,s.status,s.amount_paise,s.currency,s.razorpay_order_id,s.razorpay_payment_id,s.starts_at,s.ends_at,s.created_at FROM subscriptions s LEFT JOIN access_users u ON u.id=s.user_id ORDER BY s.created_at DESC,s.id DESC LIMIT ?",Math.min(Math.max(limit,1),500));
    }

    /** Admin-only per-customer usage summary, including last activity. */
    public List<Map<String,Object>> customerUsage(int limit) {
        String now=Instant.now().toString();
        String month=YearMonth.now(ZoneOffset.UTC).toString();
        String sql="SELECT u.id,u.name,u.username,u.email,t.name AS team,u.access_mode,u.enabled,u.self_signup,u.trial_requests_used,u.created_at,"
                +"(SELECT s.plan FROM subscriptions s WHERE s.user_id=u.id AND s.status='active' AND s.ends_at>? ORDER BY s.ends_at DESC LIMIT 1) AS subscription_plan,"
                +"(SELECT s.ends_at FROM subscriptions s WHERE s.user_id=u.id AND s.status='active' AND s.ends_at>? ORDER BY s.ends_at DESC LIMIT 1) AS subscription_ends_at,"
                +"COALESCE((SELECT SUM(m.requests) FROM usage_monthly m WHERE m.virtual_key=u.virtual_key AND m.month=?),0) AS requests_month,"
                +"COALESCE((SELECT SUM(m.prompt_tokens+m.completion_tokens) FROM usage_monthly m WHERE m.virtual_key=u.virtual_key AND m.month=?),0) AS tokens_month,"
                +"COALESCE((SELECT SUM(m.cost_usd) FROM usage_monthly m WHERE m.virtual_key=u.virtual_key AND m.month=?),0) AS spend_month,"
                +"(SELECT MAX(l.created_at) FROM request_logs l WHERE l.virtual_key=u.virtual_key) AS last_active "
                +"FROM access_users u JOIN teams t ON t.id=u.team_id ORDER BY last_active DESC NULLS LAST,u.id DESC LIMIT ?";
        return db.queryForList(sql,now,now,month,month,month,Math.min(Math.max(limit,1),500));
    }

    private long count(String sql,Object... args){Long value=db.queryForObject(sql,Long.class,args);return value==null?0:value;}
    private long longTotal(String sql,Object... args){Long value=db.queryForObject(sql,Long.class,args);return value==null?0:value;}
    private double doubleTotal(String sql,Object... args){Double value=db.queryForObject(sql,Double.class,args);return value==null?0:value;}

    private List<String> readStrings(Object value){try{return json.readValue(String.valueOf(value),new TypeReference<>(){});}catch(Exception ex){return List.of();}}
    private List<String> strings(Object value){if(value instanceof List<?> list){List<String> out=new ArrayList<>(list.size());for(Object item:list)out.add(String.valueOf(item));return out;}return new ArrayList<>();}
    private String text(Map<String,Object> b,String k){Object v=b==null?null:b.get(k);return v==null?"":String.valueOf(v);}
    private double number(Map<String,Object>b,String k,double fallback){try{return Double.parseDouble(String.valueOf(b.get(k)));}catch(Exception ex){return fallback;}}
    private boolean bool(Map<String,Object>b,String k,boolean fallback){return b==null||b.get(k)==null?fallback:Boolean.parseBoolean(String.valueOf(b.get(k)));}
    private String policy(Map<String,Object>b,String k,String fallback){String p=text(b,k).toLowerCase(Locale.ROOT);if(p.isBlank())return fallback;if(!Set.of("off","flag","block","redact").contains(p))throw bad(k+" must be off, flag, block, or redact.");return p;}
    private String randomHex(int chars){StringBuilder s=new StringBuilder(chars);for(int i=0;i<chars;i++)s.append("0123456789abcdef".charAt(random.nextInt(16)));return s.toString();}
    private IllegalArgumentException bad(String message){return new IllegalArgumentException(message);}
}
