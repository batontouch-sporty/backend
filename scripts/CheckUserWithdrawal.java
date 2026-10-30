import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import com.fasterxml.jackson.databind.*;

/** USR-004/005 against the running local server; only a newly created test account. */
class CheckUserWithdrawal {
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpClient HTTP=HttpClient.newHttpClient();
    static final StringBuilder REPORT=new StringBuilder("# USR-004 / USR-005 live API test\n\n");
    static void log(String s){System.out.println(s);REPORT.append(s).append("\n\n");}
    static HttpResponse<String> request(String method,String path,String body,String token)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://localhost:8000/api"+path)).timeout(java.time.Duration.ofSeconds(20)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        return HTTP.send(b.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    static List<String> state(Connection c,long id)throws Exception{
        try(var s=c.prepareStatement("SELECT status,withdrawn_at,updated_at FROM `user` WHERE id=?")){
            s.setLong(1,id);try(var r=s.executeQuery()){if(!r.next())throw new IllegalStateException("User missing");return Arrays.asList(r.getString(1),r.getString(2),r.getString(3));}
        }
    }
    public static void main(String[] args)throws Exception{
        Properties p=new Properties();try(var r=Files.newBufferedReader(Path.of(".env"))){p.load(r);}
        Class.forName("org.mariadb.jdbc.Driver");
        try(var c=DriverManager.getConnection(System.getenv().getOrDefault("DB_URL",p.getProperty("DB_URL")),System.getenv().getOrDefault("DB_USERNAME",p.getProperty("DB_USERNAME")),System.getenv().getOrDefault("DB_PASSWORD",p.getProperty("DB_PASSWORD")))){
            if(!"sporty".equals(c.getCatalog()))throw new IllegalStateException("Wrong database");
            String suffix=UUID.randomUUID().toString().substring(0,8);
            var credentials=JSON.createObjectNode().put("email","tc-usr004-"+suffix+"@example.com").put("password","password");
            var signup=credentials.deepCopy().put("nickname","TC-USR004-"+suffix).put("gender","MALE").put("district","SEONGDONG");signup.putArray("sportTypes");
            if(request("POST","/users",signup.toString(),null).statusCode()!=201)throw new IllegalStateException("Signup failed");
            var login=request("POST","/auth/login",credentials.toString(),null);
            if(login.statusCode()!=200)throw new IllegalStateException("Login failed");
            var data=JSON.readTree(login.body());String token=data.path("accessToken").asText();long id=data.path("userId").asLong();
            boolean withdrawn=false;
            try{
                var before=state(c,id);
                if(!"ACTIVE".equals(before.get(0))||before.get(1)!=null)throw new IllegalStateException("Invalid initial state");
                log("Test account userId="+id+"; login HTTP 200; initial status=ACTIVE, withdrawn_at=NULL");
                var wrong=request("DELETE","/users/me","{\"password\":\"wrongpassword\"}",token);
                var afterWrong=state(c,id);
                boolean passWrong=wrong.statusCode()==400&&wrong.body().contains("PASSWORD_MISMATCH")&&before.equals(afterWrong);
                log("USR-005 "+(passWrong?"PASS":"FAIL")+": HTTP "+wrong.statusCode()+" body="+wrong.body()+"; status="+afterWrong.get(0)+", withdrawn_at="+afterWrong.get(1)+"; status/date/updated_at unchanged="+before.equals(afterWrong));
                var correct=request("DELETE","/users/me","{\"password\":\"password\"}",token);
                var after=state(c,id);withdrawn="WITHDRAWN".equals(after.get(0));
                boolean passCorrect=correct.statusCode()==204&&withdrawn&&after.get(1)!=null;
                log("USR-004 "+(passCorrect?"PASS":"FAIL")+": HTTP "+correct.statusCode()+"; status="+after.get(0)+", withdrawn_at="+after.get(1));
                if(!passWrong||!passCorrect)throw new IllegalStateException("Test failed; inspect results");
            }finally{
                if(!withdrawn)log("Cleanup withdrawal HTTP "+request("DELETE","/users/me","{\"password\":\"password\"}",token).statusCode());
                Files.writeString(Path.of("scripts/USR-004-005-results.md"),REPORT.toString(),StandardCharsets.UTF_8);
            }
        }
    }
}
