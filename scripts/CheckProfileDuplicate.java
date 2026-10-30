import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import com.fasterxml.jackson.databind.*;

class CheckProfileDuplicate {
 static final ObjectMapper J=new ObjectMapper(); static final HttpClient H=HttpClient.newHttpClient();
 static HttpResponse<String> req(String m,String p,String b,String t)throws Exception{var x=HttpRequest.newBuilder(URI.create("http://localhost:8000/api"+p)).header("Content-Type","application/json");if(t!=null)x.header("Authorization","Bearer "+t);return H.send(x.method(m,HttpRequest.BodyPublishers.ofString(b)).build(),HttpResponse.BodyHandlers.ofString());}
 public static void main(String[] a)throws Exception{
  String s=UUID.randomUUID().toString().substring(0,8),pw="Tc!"+UUID.randomUUID().toString().substring(0,15); Properties p=new Properties();try(var r=Files.newBufferedReader(Path.of(".env"))){p.load(r);}
  String[][] users={{"tc-pr002-a-"+s+"@example.com","PR002A-"+s},{"tc-pr002-b-"+s+"@example.com","중복닉네임"}};List<String> tokens=new ArrayList<>();
  try(Connection c=DriverManager.getConnection(System.getenv().getOrDefault("DB_URL",p.getProperty("DB_URL")),System.getenv().getOrDefault("DB_USERNAME",p.getProperty("DB_USERNAME")),System.getenv().getOrDefault("DB_PASSWORD",p.getProperty("DB_PASSWORD")))){
   for(var u:users){var x=J.createObjectNode().put("email",u[0]).put("password",pw).put("nickname",u[1]).put("gender","MALE").put("district","SEONGDONG");x.putArray("sportTypes").add("FUTSAL");var sg=req("POST","/users",x.toString(),null);if(sg.statusCode()!=201)throw new IllegalStateException("signup "+sg.statusCode()+sg.body());var lg=req("POST","/auth/login",J.createObjectNode().put("email",u[0]).put("password",pw).toString(),null);tokens.add(J.readTree(lg.body()).path("accessToken").asText());}
   String token=tokens.get(0), before;
   try(var q=c.prepareStatement("SELECT nickname,district FROM profile p JOIN `user` u ON u.id=p.user_id WHERE u.email=?")){q.setString(1,users[0][0]);try(var r=q.executeQuery()){r.next();before=r.getString(1)+"/"+r.getString(2);}}
   var body=J.createObjectNode().put("nickname","중복닉네임").put("district","DOBONG");body.putArray("sportTypes").add("SOCCER").add("BADMINTON");var res=req("PUT","/profiles/me",body.toString(),token);String after;
   try(var q=c.prepareStatement("SELECT nickname,district FROM profile p JOIN `user` u ON u.id=p.user_id WHERE u.email=?")){q.setString(1,users[0][0]);try(var r=q.executeQuery()){r.next();after=r.getString(1)+"/"+r.getString(2);}}
   boolean pass=res.statusCode()==409&&res.body().contains("DUPLICATE_NICKNAME")&&before.equals(after);System.out.println("PR-002 duplicate nickname: "+(pass?"PASS":"FAIL")+" HTTP "+res.statusCode()+" body="+res.body()+"; profile unchanged="+before.equals(after));
  }finally{}
 }
}
