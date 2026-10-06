package top.sshh.bililiverecoder.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.sshh.bililiverecoder.entity.ExportConfigParams;
import top.sshh.bililiverecoder.service.backup.BackupService;
import java.nio.file.*;
import java.util.Map;

@RestController
@RequestMapping("/room")
public class BackupController {
    private final BackupService backups;
    public BackupController(BackupService backups) { this.backups=backups; }

    @PostMapping("/exportConfig")
    public void export(@RequestBody ExportConfigParams params,@RequestHeader(value="X-Config-Task-Id",required=false) String taskId,HttpServletResponse response) throws Exception {
        var artifact=backups.export(params,taskId==null?java.util.UUID.randomUUID().toString():taskId);
        try {
            response.setContentType("application/zip");response.setContentLengthLong(artifact.bytes());
            response.setHeader("Content-Disposition","attachment; filename=biliupForJava-backup-"+System.currentTimeMillis()+".zip");
            response.setHeader("X-Backup-Format","3");response.setHeader("X-Backup-SHA256",artifact.sha256());
            Files.copy(artifact.path(),response.getOutputStream());
        }finally {Files.deleteIfExists(artifact.path());}
    }
    @PostMapping("/backup/session")
    public Map<String,Object> create(@RequestBody Map<String,Long> input) throws Exception {return backups.create(input.getOrDefault("size",0L));}
    @PutMapping("/backup/session/{id}/chunks/{index}")
    public Map<String,Object> chunk(@PathVariable String id,@PathVariable int index,HttpServletRequest request)throws Exception {
        backups.chunk(id,index,request.getInputStream());return backups.status(id);
    }
    @PostMapping("/backup/session/{id}/prepare")
    public Map<String,Object> prepare(@PathVariable String id) {backups.prepare(id);return backups.status(id);}
    @GetMapping(value="/backup/quarantine/{id}/payload",produces="application/json")
    public String quarantinePayload(@PathVariable long id) {return backups.quarantinePayload(id);}
    @GetMapping("/backup/quarantine")
    public Map<String,Object> quarantine(@RequestParam(defaultValue="0") int page) {return backups.quarantined(page);}
    @GetMapping({"/backup/status/{id}","/configTask/status/{id}"})
    public Map<String,Object> status(@PathVariable String id) {return backups.status(id);}
    @GetMapping("/backup/session/{id}/preview")
    public Map<String,Object> preview(@PathVariable String id,@RequestParam(defaultValue="0") int page) {return backups.preview(id,page);}
    @PostMapping("/backup/session/{id}/commit")
    public Map<String,Object> commit(@PathVariable String id,@RequestBody BackupService.CommitRequest input) {backups.commit(id,input);return backups.status(id);}
    @PostMapping("/backup/cancel/{id}")
    public Map<String,Object> cancel(@PathVariable String id)throws Exception {backups.cancel(id);return backups.status(id);}
    @GetMapping("/backup/session/{id}/report")
    public Map<String,Object> report(@PathVariable String id) {return backups.status(id);}

    @PostMapping("/uploadConfig")
    public Map<String,Object> legacyUpload(@RequestParam("file") MultipartFile file)throws Exception {
        var result=backups.create(file.getSize());String id=result.get("taskId").toString();
        try(var input=file.getInputStream()) {
            int index=0;byte[] chunk;
            while((chunk=input.readNBytes(16*1024*1024)).length>0)backups.chunk(id,index++,new java.io.ByteArrayInputStream(chunk));
            backups.prepare(id);return Map.of("taskId",id,"requiresReview",true);
        }catch(Exception e){backups.cancel(id);throw e;}
    }
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String,Object> failure(RuntimeException error) {return Map.of("success",false,"message",error.getMessage());}
}
