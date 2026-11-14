package com.dhruva.airtribe.prism;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@RestController
public class ChatAppController {
    private final ManagedAccessService managed;
    private final PrismService prism;
    private final AuthService auth;
    private final EntitlementService billing;
    private final ObjectMapper json=new ObjectMapper();
    public ChatAppController(ManagedAccessService managed,PrismService prism,AuthService auth,EntitlementService billing){this.managed=managed;this.prism=prism;this.auth=auth;this.billing=billing;}

    @PostMapping("/app/login") public ResponseEntity<?> login(@RequestBody Map<String,Object> body){try{return ResponseEntity.ok(managed.login(String.valueOf(body.getOrDefault("username","")),String.valueOf(body.getOrDefault("password",""))));}catch(AuthService.AuthException ex){return ResponseEntity.status(ex.status()).body(error(ex.getMessage()));}}
    @PostMapping("/app/signup") public ResponseEntity<?> signup(@RequestBody Map<String,Object> body){try{return ResponseEntity.status(201).body(managed.selfSignup(body));}catch(AuthService.AuthException ex){return ResponseEntity.status(ex.status()).body(error(ex.getMessage()));}catch(IllegalArgumentException ex){return ResponseEntity.badRequest().body(error(ex.getMessage()));}}
    @GetMapping("/app/me") public ResponseEntity<?> me(@RequestHeader(value="Authorization",required=false) String header){try{long id=managed.requireChatUser(header);Map<String,Object> user=new LinkedHashMap<>(managed.chatProfile(id));user.remove("virtual_key");return ResponseEntity.ok(Map.of("user",user));}catch(AuthService.AuthException ex){return ResponseEntity.status(ex.status()).body(error(ex.getMessage()));}}
    @GetMapping("/app/usage") public ResponseEntity<?> usage(@RequestHeader(value="Authorization",required=false) String header){try{return ResponseEntity.ok(Map.of("usage",managed.chatUsage(managed.requireChatUser(header))));}catch(AuthService.AuthException ex){return ResponseEntity.status(ex.status()).body(error(ex.getMessage()));}}
    @GetMapping("/app/conversations") public ResponseEntity<?> conversations(@RequestHeader(value="Authorization",required=false) String header){try{return ResponseEntity.ok(Map.of("conversations",managed.conversations(managed.requireChatUser(header))));}catch(AuthService.AuthException ex){return ResponseEntity.status(ex.status()).body(error(ex.getMessage()));}}
    @PostMapping("/app/conversations") public ResponseEntity<?> createConversation(@RequestHeader(value="Authorization",required=false) String header,@RequestBody(required=false) Map<String,Object> body){try{long id=managed.requireChatUser(header);return ResponseEntity.status(201).body(Map.of("conversation",sanitizeConversation(managed.conversation(id,managed.createConversation(id,body==null?null:String.valueOf(body.get("title")))))));}catch(AuthService.AuthException ex){return ResponseEntity.status(ex.status()).body(error(ex.getMessage()));}}
    @GetMapping("/app/conversations/{id}") public ResponseEntity<?> conversation(@RequestHeader(value="Authorization",required=false) String header,@PathVariable long id){try{return ResponseEntity.ok(Map.of("conversation",sanitizeConversation(managed.conversation(managed.requireChatUser(header),id))));}catch(AuthService.AuthException ex){return ResponseEntity.status(ex.status()).body(error(ex.getMessage()));}}
    @PatchMapping("/app/conversations/{id}") public ResponseEntity<?> rename(@RequestHeader(value="Authorization",required=false) String header,@PathVariable long id,@RequestBody Map<String,Object> body){try{long user=managed.requireChatUser(header);managed.renameConversation(user,id,String.valueOf(body.getOrDefault("title","")));return ResponseEntity.ok(Map.of("conversation",sanitizeConversation(managed.conversation(user,id))));}catch(AuthService.AuthException ex){return ResponseEntity.status(ex.status()).body(error(ex.getMessage()));}catch(IllegalArgumentException ex){return ResponseEntity.badRequest().body(error(ex.getMessage()));}}
    @DeleteMapping("/app/conversations/{id}") public ResponseEntity<?> delete(@RequestHeader(value="Authorization",required=false) String header,@PathVariable long id){try{managed.deleteConversation(managed.requireChatUser(header),id);return ResponseEntity.ok(Map.of("deleted",true));}catch(AuthService.AuthException ex){return ResponseEntity.status(ex.status()).body(error(ex.getMessage()));}}

    @PostMapping("/app/chat") public ResponseEntity<StreamingResponseBody> chat(@RequestHeader(value="Authorization",required=false) String header,@RequestBody Map<String,Object> body){
        long userId;Map<String,Object> profile;try{userId=managed.requireChatUser(header);profile=managed.chatProfile(userId);}catch(AuthService.AuthException ex){return errorStream(ex.status(),ex.getMessage());}
        if("api".equals(profile.get("access_mode")))return errorStream(403,"This account has API-only access.");
        BillingService.Entitlement entitlement=billing.entitlement(userId);
        if(!entitlement.allowed())return errorStream(402,"Your free demo has ended. Subscribe to Pro or Enterprise to keep chatting.","subscription_required");
        String key=String.valueOf(profile.get("virtual_key"));String model=String.valueOf(body.getOrDefault("model","auto"));List<String> allowed=readList(profile.get("model_allowlist"));if(!allowed.contains(model))return errorStream(403,"This model is not allowed for your account.");
        Map<String,Object> gatewayBody=new LinkedHashMap<>(body);gatewayBody.remove("conversation_id");gatewayBody.remove("verify");
        PrismService.Tenant tenant;try{tenant=prism.authenticateChat(key);prism.validateRequest(gatewayBody);prism.applyPromptPolicies(tenant,gatewayBody);}catch(PrismService.GatewayException ex){return errorStream(ex.status,prism.redactModelDetails(ex.getMessage()));}
        long conversationId;try{Object raw=body.get("conversation_id");conversationId=raw instanceof Number n?managed.conversation(userId,n.longValue())!=null?n.longValue():0:0;}catch(Exception ex){conversationId=0;}
        if(conversationId==0)conversationId=managed.createConversation(userId,title(body));
        String userPrompt=prism.prompt(body);if(!userPrompt.isBlank())managed.appendMessage(userId,conversationId,"user",lastUser(body));
        if(Boolean.TRUE.equals(body.get("verify")))return verifiedChat(tenant,gatewayBody,profile,userId,conversationId,model);
        PrismService.BudgetLease lease=prism.acquireBudgetLease(tenant);PrismService.StreamSession stream;try{prism.admit(tenant,model);if(!billing.reserveRequest(userId)){lease.close();return errorStream(402,"Your free demo has ended. Subscribe to Pro or Enterprise to keep chatting.","subscription_required");}stream=prism.beginStream(tenant,gatewayBody,true);}catch(PrismService.GatewayException ex){lease.close();return errorStream(ex.status,prism.redactModelDetails(ex.getMessage()));}
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.TEXT_EVENT_STREAM);headers.setCacheControl(CacheControl.noCache());headers.set("x-prism-conversation-id",Long.toString(conversationId));headers.set("x-prism-model-hidden","true");
        long cid=conversationId;StreamingResponseBody output=out->{ByteArrayOutputStream copy=new ByteArrayOutputStream();OutputStream tee=new FilterOutputStream(out){@Override public void write(int b)throws IOException{out.write(b);copy.write(b);}@Override public void write(byte[] b,int off,int len)throws IOException{out.write(b,off,len);copy.write(b,off,len);}@Override public void flush()throws IOException{out.flush();}};try{prism.writeStream(stream,tee);String answer=assistantFromSse(copy.toString(StandardCharsets.UTF_8));if(!answer.isBlank())managed.appendMessage(userId,cid,"assistant",answer);}finally{lease.close();}};
        return ResponseEntity.ok().headers(headers).body(output);
    }

    private ResponseEntity<StreamingResponseBody> verifiedChat(PrismService.Tenant tenant,Map<String,Object> body,Map<String,Object> profile,long userId,long conversationId,String model){
        List<String> checkers=readList(profile.get("verify_models")).stream().filter(x->!x.equals(model)&&!x.equals("auto")).toList();if(checkers.isEmpty())return errorStream(400,"Multi-model verification needs a checker model configured on the user's plan.");
        PrismService.BudgetLease lease=prism.acquireBudgetLease(tenant);try{prism.admit(tenant,model);if(!billing.reserveRequest(userId))return errorStream(402,"Your free demo has ended. Subscribe to Pro or Enterprise to keep chatting.","subscription_required");PrismService.Result draft=prism.complete(tenant,body,true);String draftText=prism.redactModelDetails(assistantFromPayload(draft.payload()));String checkerModel=checkers.get(0);Map<String,Object> checkBody=new LinkedHashMap<>(body);List<Map<String,Object>> messages=new ArrayList<>();Object raw=body.get("messages");if(raw instanceof List<?> xs)for(Object x:xs)if(x instanceof Map<?,?> m){Map<String,Object> item=new LinkedHashMap<>();m.forEach((k,v)->item.put(String.valueOf(k),v));messages.add(item);}messages.add(Map.of("role","assistant","content",draftText));messages.add(Map.of("role","user","content","Review the answer above for factual errors. Return a corrected, concise answer; preserve correct details. If it is already correct, return it unchanged."));checkBody.put("messages",messages);checkBody.put("model",checkerModel);prism.admit(tenant,checkerModel);PrismService.Result checked=prism.complete(tenant,checkBody,true);String finalText=prism.redactModelDetails(assistantFromPayload(checked.payload()));managed.appendMessage(userId,conversationId,"assistant",finalText);HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.TEXT_EVENT_STREAM);headers.setCacheControl(CacheControl.noCache());headers.set("x-prism-conversation-id",Long.toString(conversationId));headers.set("x-prism-verified","true");headers.set("x-prism-model-hidden","true");StreamingResponseBody stream=out->writeSse(out,finalText);return ResponseEntity.ok().headers(headers).body(stream);}catch(PrismService.GatewayException ex){return errorStream(ex.status,prism.redactModelDetails(ex.getMessage()));}finally{lease.close();}
    }

    private ResponseEntity<StreamingResponseBody> errorStream(int status,String message){
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(out->out.write(json.writeValueAsBytes(error(message))));
    }

    private ResponseEntity<StreamingResponseBody> errorStream(int status,String message,String code){
        Map<String,Object> detail=new LinkedHashMap<>();
        detail.put("message",message);
        detail.put("code",code);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(out->out.write(json.writeValueAsBytes(Map.of("error",detail))));
    }

    /** Stored assistant turns can predate the privacy guardrail; mask them on read. */
    private Map<String,Object> sanitizeConversation(Map<String,Object> conversation){
        Map<String,Object> clean=new LinkedHashMap<>(conversation);
        Object raw=clean.get("messages");
        if(raw instanceof List<?> messages){
            List<Object> out=new ArrayList<>();
            for(Object item:messages){
                if(item instanceof Map<?,?> message){
                    Map<String,Object> copy=new LinkedHashMap<>();
                    message.forEach((k,v)->copy.put(String.valueOf(k),v));
                    if("assistant".equals(copy.get("role"))&&copy.get("content") instanceof String content)copy.put("content",prism.redactModelDetails(content));
                    out.add(copy);
                }else out.add(item);
            }
            clean.put("messages",out);
        }
        return clean;
    }

    private void writeSse(OutputStream out,String text)throws IOException{writeEvent(out,Map.of("id","chatcmpl-verified","object","chat.completion.chunk","choices",List.of(Map.of("index",0,"delta",Map.of("role","assistant"),"finish_reason",""))));for(int i=0;i<text.length();i+=40)writeEvent(out,Map.of("id","chatcmpl-verified","object","chat.completion.chunk","choices",List.of(Map.of("index",0,"delta",Map.of("content",text.substring(i,Math.min(i+40,text.length()))),"finish_reason",""))));writeEvent(out,Map.of("id","chatcmpl-verified","object","chat.completion.chunk","choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason","stop"))));out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));out.flush();}
    private void writeEvent(OutputStream out,Map<String,Object> value)throws IOException{out.write(("data: "+json.writeValueAsString(value)+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();}
    private String assistantFromSse(String raw){StringBuilder out=new StringBuilder();for(String line:raw.split("\\R")){if(!line.startsWith("data: ")||line.equals("data: [DONE]"))continue;try{Map<String,Object> event=json.readValue(line.substring(6),new TypeReference<>(){});Object choices=event.get("choices");if(choices instanceof List<?> l&&!l.isEmpty()&&l.get(0) instanceof Map<?,?> c&&c.get("delta") instanceof Map<?,?> d&&d.get("content")!=null)out.append(d.get("content"));}catch(Exception ignored){}}return out.toString();}
    private String assistantFromPayload(Map<String,Object> payload){try{Map<?,?> choice=(Map<?,?>)((List<?>)payload.get("choices")).get(0);Map<?,?> message=(Map<?,?>)choice.get("message");return String.valueOf(message.get("content"));}catch(Exception ex){return "";}}
    private List<String> readList(Object value){try{return json.readValue(String.valueOf(value),new TypeReference<>(){});}catch(Exception ex){return List.of();}}
    private String lastUser(Map<String,Object> body){Object raw=body.get("messages");if(raw instanceof List<?> xs)for(int i=xs.size()-1;i>=0;i--)if(xs.get(i) instanceof Map<?,?> m&&"user".equals(m.get("role")))return String.valueOf(m.get("content"));return "";}
    private String title(Map<String,Object> body){String value=lastUser(body).replaceAll("\\s+"," ").trim();return value.length()>48?value.substring(0,48):value.isBlank()?"New conversation":value;}
    private Map<String,Object> error(String message){return Map.of("error",Map.of("message",message));}
}
