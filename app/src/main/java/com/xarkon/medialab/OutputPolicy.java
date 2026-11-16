package com.xarkon.medialab;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** SAF output transactions. Never truncate or delete the original before a verified new file exists. */
final class OutputPolicy {
    static final String PREF="medialab_output", TREE="tree", NAMES="original_names", WORKERS="workers";
    static final class Saved {
        final Uri uri; final boolean replaced; final String warning;
        Saved(Uri uri,boolean replaced,String warning){this.uri=uri;this.replaced=replaced;this.warning=warning;}
    }
    static String name(String source,String format,boolean original,int index){
        if(!original)return ConvertEngine.fileName(source,format,index);
        String stem=source;int dot=stem.lastIndexOf('.');
        if(dot>0)stem=stem.substring(0,dot);
        stem=stem.replace('/','_').replace('\\','_').replaceAll("[\\p{Cntrl}]","_").trim();
        if(stem.isEmpty()||stem.equals(".")||stem.equals(".."))stem="media";
        return stem+"."+format;
    }
    static String folderLabel(Context ctx,Uri tree){
        if(tree==null)return "Загрузки / MediaLAB";
        try{
            String id=DocumentsContract.getTreeDocumentId(tree);
            Uri root=DocumentsContract.buildDocumentUriUsingTree(tree,id);
            String label=display(ctx.getContentResolver(),root);
            return label==null?id:label+" · "+id;
        }catch(Exception ex){return "Выбранная папка (проверьте доступ)";}
    }
    static boolean hasWrite(Context ctx,Uri tree){
        if(tree==null)return true;
        for(UriPermission permission:ctx.getContentResolver().getPersistedUriPermissions())
            if(permission.getUri().equals(tree)&&permission.isReadPermission()&&permission.isWritePermission())return true;
        return false;
    }
    private static void check(ConvertEngine.Canceled c)throws IOException{
        if(c.canceled())throw new IOException("Операция отменена");
    }
    private static String display(ContentResolver cr,Uri uri)throws IOException{
        try(Cursor c=cr.query(uri,new String[]{Document.COLUMN_DISPLAY_NAME},null,null,null)){
            return c!=null&&c.moveToFirst()?c.getString(0):null;
        }catch(Exception ex){throw new IOException("Не удалось прочитать имя документа",ex);}
    }
    private static long flags(ContentResolver cr,Uri uri)throws IOException{
        try(Cursor c=cr.query(uri,new String[]{Document.COLUMN_FLAGS},null,null,null)){
            if(c==null||!c.moveToFirst())throw new IOException("Документ недоступен");
            return c.getLong(0);
        }
    }
    private static String id(Uri uri){return DocumentsContract.getDocumentId(uri);}
    private static boolean same(Uri a,Uri b){
        if(a==null||b==null)return false;
        try{return Objects.equals(a.getAuthority(),b.getAuthority())&&id(a).equals(id(b));}
        catch(Exception ex){return a.equals(b);}
    }
    private static final class Parent {
        final Uri tree,uri,source;
        Parent(Uri t,String folder,String sourceId){
            tree=t;uri=DocumentsContract.buildDocumentUriUsingTree(t,folder);
            source=DocumentsContract.buildDocumentUriUsingTree(t,sourceId);
        }
    }
    private static Uri childNamed(ContentResolver cr,Uri tree,Uri parent,String name)throws IOException{
        Uri list=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id(parent));
        try(Cursor c=cr.query(list,new String[]{Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(c==null)throw new IOException("Не удалось прочитать содержимое папки");
            while(c.moveToNext())if(name.equalsIgnoreCase(c.getString(1)))
                return DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0));
            return null;
        }
    }
    private static boolean containsId(ContentResolver cr,Uri tree,String folder,String sourceId){
        try(Cursor c=cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,folder),
                new String[]{Document.COLUMN_DOCUMENT_ID},null,null,null)){
            if(c==null)return false;
            while(c.moveToNext())if(sourceId.equals(c.getString(0)))return true;
        }catch(Exception ignored){}
        return false;
    }
    private static Parent findParent(Context ctx,Uri original){
        ContentResolver cr=ctx.getContentResolver();
        final String sourceId;
        try{sourceId=id(original);}catch(Exception ex){return null;}
        for(UriPermission grant:cr.getPersistedUriPermissions()){
            Uri tree=grant.getUri();
            if(!grant.isReadPermission()||!grant.isWritePermission()||!DocumentsContract.isTreeUri(tree)
                    ||!Objects.equals(tree.getAuthority(),original.getAuthority()))continue;
            try{
                String root=DocumentsContract.getTreeDocumentId(tree);
                if(containsId(cr,tree,root,sourceId))return new Parent(tree,root,sourceId);
                // Ask the provider for the actual document path, not a guessed path.
                try{
                    DocumentsContract.Path path=DocumentsContract.findDocumentPath(cr,DocumentsContract.buildDocumentUriUsingTree(tree,sourceId));
                    List<String> ids=path==null?null:path.getPath();
                    if(ids!=null&&ids.size()>=2&&ids.get(ids.size()-1).equals(sourceId)){
                        String parent=ids.get(ids.size()-2);
                        if(containsId(cr,tree,parent,sourceId))return new Parent(tree,parent,sourceId);
                    }
                }catch(Exception ignored){}
                // Android external storage document IDs are volume:path. Verify membership.
                if("com.android.externalstorage.documents".equals(tree.getAuthority())){
                    int slash=sourceId.lastIndexOf('/'),colon=sourceId.indexOf(':');
                    String parent=slash>colon?sourceId.substring(0,slash):colon>=0?sourceId.substring(0,colon+1):"";
                    if(!parent.isEmpty()&&(parent.equals(root)||parent.startsWith(root.endsWith(":")?root:root+"/"))
                            &&containsId(cr,tree,parent,sourceId))return new Parent(tree,parent,sourceId);
                }
            }catch(Exception ignored){}
        }
        return null;
    }
    static boolean canReplace(Context ctx,Uri source){return findParent(ctx,source)!=null;}
    private static byte[] hash(InputStream in,ConvertEngine.Canceled c)throws IOException{
        try{
            MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[1024*1024];int n;
            while((n=in.read(b))!=-1){check(c);md.update(b,0,n);}
            return md.digest();
        }catch(java.security.NoSuchAlgorithmException ex){throw new IOException(ex);}
    }
    private static byte[] hashFile(File f,ConvertEngine.Canceled c)throws IOException{
        try(InputStream in=new FileInputStream(f)){return hash(in,c);}
    }
    private static byte[] hashUri(ContentResolver cr,Uri uri,ConvertEngine.Canceled c)throws IOException{
        try(InputStream in=cr.openInputStream(uri)){
            if(in==null)throw new IOException("Не удалось проверить записанный файл");
            return hash(in,c);
        }
    }
    private static void verify(ContentResolver cr,Uri uri,byte[] expected,ConvertEngine.Canceled c)throws IOException{
        if(!MessageDigest.isEqual(expected,hashUri(cr,uri,c)))throw new IOException("Контрольная сумма записанного файла не совпадает");
    }
    private static void write(ContentResolver cr,Uri target,File result,ConvertEngine.Canceled c)throws IOException{
        try(InputStream in=new FileInputStream(result);OutputStream out=cr.openOutputStream(target,"w")){
            if(out==null)throw new IOException("Нет доступа к записи");
            byte[] b=new byte[1024*1024];int n;
            while((n=in.read(b))!=-1){check(c);out.write(b,0,n);}
            out.flush();
        }
    }
    private static Uri create(ContentResolver cr,Uri parent,String mime,String name)throws IOException{
        Uri uri=DocumentsContract.createDocument(cr,parent,mime,name);
        if(uri==null)throw new IOException("Провайдер не создал файл");
        return uri;
    }
    private static Uri rename(ContentResolver cr,Uri uri,String name)throws IOException{
        Uri next=DocumentsContract.renameDocument(cr,uri,name);
        if(next==null)throw new IOException("Провайдер не разрешил переименование");
        return next;
    }
    private static void removeQuietly(ContentResolver cr,Uri uri){
        if(uri!=null)try{DocumentsContract.deleteDocument(cr,uri);}catch(Exception ignored){}
    }
    private static String unique(ContentResolver cr,Uri tree,Uri parent,String name)throws IOException{
        if(childNamed(cr,tree,parent,name)==null)return name;
        int dot=name.lastIndexOf('.');String stem=dot>0?name.substring(0,dot):name,ext=dot>0?name.substring(dot):"";
        for(int i=1;i<10000;i++){String next=stem+" ("+i+")"+ext;if(childNamed(cr,tree,parent,next)==null)return next;}
        throw new IOException("Слишком много одноимённых файлов");
    }
    // Serialize publication only, not encoding: simultaneous workers cannot choose the same name.
    static synchronized Saved save(Context ctx,File result,File originalCopy,ConvertEngine.Source source,
            String format,String mime,int index,Uri tree,boolean originalNames,boolean replace,boolean kept,
            ConvertEngine.Canceled canceled)throws IOException{
        check(canceled);
        if(result==null||!result.isFile()||result.length()<=0)throw new IOException("Результат пуст");
        if(replace&&kept)return new Saved(source.uri,false,"ASC PRO оставил исходник без изменений");
        String targetName=name(source.name,format,originalNames||replace,index);
        if(kept&&(originalNames||replace))targetName=source.name;
        if(!replace&&tree==null)return new Saved(ConvertEngine.save(ctx,result,targetName,mime,canceled),false,"");
        ContentResolver cr=ctx.getContentResolver();
        Parent parent;
        if(replace){
            parent=findParent(ctx,source.uri);
            if(parent==null)throw new IOException("Для замены предоставьте доступ к исходной папке. Оригинал не изменён");
        }else{
            if(!hasWrite(ctx,tree))throw new IOException("Нет сохранённого доступа к папке назначения. Выберите её снова");
            String root=DocumentsContract.getTreeDocumentId(tree);
            parent=new Parent(tree,root,root);
            targetName=unique(cr,tree,parent.uri,targetName);
        }
        if((flags(cr,parent.uri)&Document.FLAG_DIR_SUPPORTS_CREATE)==0)throw new IOException("Папка не поддерживает создание файлов");
        Uri collision=childNamed(cr,parent.tree,parent.uri,targetName);
        if(replace&&collision!=null&&!same(collision,parent.source))throw new IOException("Файл "+targetName+" уже существует. Оригинал сохранён");
        if(replace&&(flags(cr,parent.source)&Document.FLAG_SUPPORTS_DELETE)==0)throw new IOException("Провайдер не разрешает удаление оригинала");
        boolean sameName=replace&&collision!=null&&same(collision,parent.source);
        if(sameName&&(flags(cr,parent.source)&Document.FLAG_SUPPORTS_RENAME)==0)throw new IOException("Провайдер не поддерживает безопасную замену");
        byte[] resultHash=hashFile(result,canceled);
        byte[] originalHash=replace?hashFile(originalCopy,canceled):null;
        String token=UUID.randomUUID().toString();
        Uri temp=null,backup=null,published=null;boolean committed=false;
        try{
            temp=create(cr,parent.uri,mime,"MediaLAB-temp-"+token+"."+format);
            if(replace&&same(temp,parent.source))throw new IOException("Провайдер вернул исходный документ");
            write(cr,temp,result,canceled);verify(cr,temp,resultHash,canceled);check(canceled);
            if(replace)verify(cr,parent.source,originalHash,canceled);
            // After this point complete or roll back the short publication transaction
            // even if cancellation was requested; no original is ever truncated.
            check(canceled);
            if(sameName){
                backup=rename(cr,parent.source,"MediaLAB-backup-"+token+"-"+source.name);
            }
            published=rename(cr,temp,targetName);temp=null;
            if(replace&&!targetName.equals(display(cr,published)))throw new IOException("Провайдер изменил имя результата");
            verify(cr,published,resultHash,()->false);
            if(replace){
                Uri old=sameName?backup:parent.source;
                // Verify the old bytes again before the irreversible deletion.
                verify(cr,old,originalHash,()->false);
                committed=true;
                try{
                    if(!DocumentsContract.deleteDocument(cr,old))
                        return new Saved(published,false,"Результат сохранён, оригинал/резервная копия не удалены");
                }catch(Exception ex){return new Saved(published,false,"Результат сохранён, оригинал/резервная копия не удалены");}
                return new Saved(published,true,"");
            }
            committed=true;return new Saved(published,false,"");
        }catch(Exception ex){
            if(!committed){
                // If restoration fails, keep the backup rather than deleting original bytes.
                if(published!=null)removeQuietly(cr,published);
                if(backup!=null)try{rename(cr,backup,source.name);}catch(Exception restore){
                    throw new IOException("Замена не завершена; оригинал сохранён под именем MediaLAB-backup-"+token+"-"+source.name,ex);
                }
            }
            if(ex instanceof IOException)throw (IOException)ex;
            throw new IOException("Не удалось сохранить файл: "+ex.getMessage(),ex);
        }finally{if(temp!=null&&(!replace||!same(temp,parent.source)))removeQuietly(cr,temp);}
    }
}
