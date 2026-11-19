package org.privacybubble.designqa;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Separate test UID owns input and evidence; only the exact QA-signed target may consume it. */
public final class PrivateQaProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        try{
            PackageManager pm=getContext().getPackageManager();
            int uid=pm.getApplicationInfo("org.privacybubble.app",0).uid;
            if(Binder.getCallingUid()!=uid||pm.checkSignatures("org.privacybubble.app","org.privacybubble.designqa")!=PackageManager.SIGNATURE_MATCH)throw new SecurityException();
            File dir=getContext().getFilesDir();Bundle result=new Bundle();
            if(method.equals("input")){
                File file=new File(dir,"input.json");StructStat s=Os.lstat(file.getAbsolutePath());
                if(!OsConstants.S_ISREG(s.st_mode)||s.st_uid!=android.os.Process.myUid()||(s.st_mode&0077)!=0||s.st_size>8192)throw new SecurityException();
                byte[] bytes=Files.readAllBytes(file.toPath());String text=new String(bytes,StandardCharsets.UTF_8);
                java.util.Arrays.fill(bytes,(byte)0);if(!file.delete())throw new SecurityException();
                new JSONObject(text);result.putString("value",text);return result;
            }
            if(arg==null||!arg.matches("[A-Za-z0-9._-]{1,100}"))throw new SecurityException();
            byte[] data;
            if(method.equals("report")&&arg.endsWith(".json")){
                String value=extras.getString("value");if(value==null||value.length()>65536)throw new SecurityException();
                new JSONObject(value);data=value.getBytes(StandardCharsets.UTF_8);
            }else if(method.equals("shot")&&arg.endsWith(".png")){
                data=extras.getByteArray("value");if(data==null||data.length<8||data.length>650000||data[0]!=(byte)137||data[1]!=80||data[2]!=78||data[3]!=71)throw new SecurityException();
            }else throw new SecurityException();
            File file=new File(dir,arg);try(FileOutputStream out=new FileOutputStream(file)){out.write(data);out.getFD().sync();}Os.chmod(file.getAbsolutePath(),0600);
            result.putString("value","saved");return result;
        }catch(Exception error){throw new IllegalStateException("Private QA operation failed");}
    }
    public Cursor query(Uri u,String[]p,String s,String[]a,String o){throw new SecurityException();}
    public String getType(Uri u){return null;}
    public Uri insert(Uri u,ContentValues v){throw new SecurityException();}
    public int delete(Uri u,String s,String[]a){throw new SecurityException();}
    public int update(Uri u,ContentValues v,String s,String[]a){throw new SecurityException();}
}
