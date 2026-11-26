package com.xksystem.logistics.tms;

import static org.assertj.core.api.Assertions.*;

import com.xksystem.logistics.tms.evidence.*;
import com.xksystem.logistics.tms.execution.ExecutionPhase;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

class EvidenceTest {
    private static final ExecutionEvidencePolicy.Requirements RULE=new ExecutionEvidencePolicy.Requirements(1,List.of("image/png"),65536,4096);
    private static EvidenceValidation.Progress progress(String text){return EvidenceValidation.progress(text.getBytes(StandardCharsets.UTF_8));}
    private static String command(String version,String ids){return "{\"expectedVersion\":"+version+",\"targetPhase\":\"IN_PROGRESS\",\"evidenceIds\":"+ids+"}";}
    private static byte[] png()throws Exception{var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB),"png",output);return output.toByteArray();}

    @Test void progressIdentityUsesSafeMathematicalIntegersAndCanonicalUuidSet() {
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();String ids="[\""+first+"\",\""+second+"\"]";
        var one=progress(command("1",ids));var reordered=progress(command("1.0e0","[\""+second.toString().toUpperCase(java.util.Locale.ROOT)+"\",\""+first+"\"]"));
        assertThat(one.hash()).isEqualTo(reordered.hash());assertThat(one.expectedVersion()).isEqualTo(1);assertThat(one.targetPhase()).isEqualTo(ExecutionPhase.IN_PROGRESS);
        assertThatThrownBy(()->progress(command("1","[\""+first+"\",\""+first.toString().toUpperCase(java.util.Locale.ROOT)+"\"]"))).isInstanceOf(EvidenceFailure.class);
        for(String number:List.of("-1","1.5","9007199254740992","1e10000000","1e-10000000","null","\"1\""))assertThatThrownBy(()->progress(command(number,"[]"))).isInstanceOf(EvidenceFailure.class);
    }
    @Test void strictJsonRejectsUnknownFieldsDuplicatesAndMalformedUtf8() {
        for(String raw:List.of("[]","{}","null",command("1","[]")+" {}",command("1","[]").replace("}",",\"actorId\":null}"),"{\"expectedVersion\":1,\"expectedVersion\":1,\"targetPhase\":\"IN_PROGRESS\",\"evidenceIds\":[]}",command("1","[]").replace("IN_PROGRESS","COMPLETED")))
            assertThatThrownBy(()->progress(raw)).isInstanceOf(EvidenceFailure.class);
        assertThatThrownBy(()->EvidenceValidation.progress(new byte[]{(byte)0xc3,0x28})).isInstanceOf(EvidenceFailure.class);
        assertThatThrownBy(()->progress(command("1","[\"\\ud800\"]"))).isInstanceOf(EvidenceFailure.class);
        assertThat(progress(command("9007199254740991","[]")).expectedVersion()).isEqualTo(TmsValidation.MAX);
    }
    @Test void uploadHeaderVersionHasStrictTextGrammarAndHashContainsOnlyRequestedFacts()throws Exception {
        assertThat(EvidenceValidation.headerVersion("0")).isZero();assertThat(EvidenceValidation.headerVersion("9007199254740991")).isEqualTo(TmsValidation.MAX);
        for(String value:List.of("01","+1","1e0","1.0"," 1","1 ","1\n","1,1","9007199254740992"))assertThatThrownBy(()->EvidenceValidation.headerVersion(value)).isInstanceOf(EvidenceFailure.class);
        byte[] bytes=png();String path="/api/v1/tms/my-tasks/"+UUID.randomUUID()+"/evidence";
        assertThat(EvidenceValidation.uploadHash(path,1,"image/png",bytes)).isEqualTo(EvidenceValidation.uploadHash(path,1,"image/png",bytes.clone()));
        assertThat(EvidenceValidation.uploadHash(path,1,"image/png",bytes)).isNotEqualTo(EvidenceValidation.uploadHash(path,2,"image/png",bytes));
        bytes[bytes.length-1]^=1;assertThat(EvidenceValidation.digest(bytes)).hasSize(64);
    }
    @Test void actualStreamingCapIsEnforcedBeforeMaterializingTheRestOfAChunkedBody()throws Exception {
        byte[] data=new byte[EvidenceValidation.HARD_CAP+1024];
        var input=new ByteArrayInputStream(data);
        assertThatThrownBy(()->EvidenceValidation.bounded(input,-1)).isInstanceOfSatisfying(EvidenceFailure.class,failure->{assertThat(failure.status()).isEqualTo(413);assertThat(failure.data()).isNull();});
        assertThat(input.available()).isEqualTo(1023);
        assertThat(EvidenceValidation.bounded(new ByteArrayInputStream(new byte[EvidenceValidation.HARD_CAP]),-1)).hasSize(EvidenceValidation.HARD_CAP);
        assertThatThrownBy(()->EvidenceValidation.bounded(new ByteArrayInputStream(new byte[0]),Long.MAX_VALUE)).isInstanceOf(EvidenceFailure.class);
    }
    @Test void pngChecksCrcTruncationTrailingDataAndPixelCountBeforeDecoding()throws Exception {
        byte[] valid=png();assertThatCode(()->PngEvidenceValidator.validate(valid,RULE)).doesNotThrowAnyException();
        byte[] crc=valid.clone();crc[29]^=1;
        byte[] bomb=valid.clone();ByteBuffer.wrap(bomb,16,4).putInt(Integer.MAX_VALUE);CRC32 checksum=new CRC32();checksum.update(bomb,12,17);ByteBuffer.wrap(bomb,29,4).putInt((int)checksum.getValue());
        for(byte[] invalid:List.of(new byte[0],crc,bomb,Arrays.copyOf(valid,valid.length-1),Arrays.copyOf(valid,valid.length+1)))assertThatThrownBy(()->PngEvidenceValidator.validate(invalid,RULE)).isInstanceOf(EvidenceFailure.class);
        assertThatThrownBy(()->PngEvidenceValidator.validate(valid,new ExecutionEvidencePolicy.Requirements(1,List.of("image/png"),1,4096))).isInstanceOf(EvidenceFailure.class);
    }
    @Test void pageFiltersAreBoundedStrictAndDoNotAllowIdentityOverrides() {
        var query=new LinkedMultiValueMap<String,String>();assertThat(EvidenceValidation.page(query)).isEqualTo(new EvidenceValidation.Page(0,20));
        query.add("page","100000");query.add("size","100");assertThat(EvidenceValidation.page(query).page()).isEqualTo(100000);
        query.add("page","0");assertThatThrownBy(()->EvidenceValidation.page(query)).isInstanceOf(EvidenceFailure.class);
        var unknown=new LinkedMultiValueMap<String,String>();unknown.add("driverId",UUID.randomUUID().toString());assertThatThrownBy(()->EvidenceValidation.page(unknown)).isInstanceOf(EvidenceFailure.class);
    }
    @Test void defaultBeansStayUnconfirmedAndProtectedRelationsUseOnlyTheNewNamespace() {
        var config=new UnavailableEvidenceConfiguration();assertThat(config.executionEvidencePolicy().mode()).isEqualTo(PolicyMode.UNCONFIRMED);assertThat(config.evidenceStorage().mode()).isEqualTo(PolicyMode.UNCONFIRMED);
        assertThatThrownBy(()->config.evidenceStorage().load(UUID.randomUUID())).isInstanceOf(EvidenceFailure.class);
        var reply=new EvidenceErrors().unexpected(new RuntimeException("private SQL",new SQLException("sensitive","23503")));
        assertThat(reply.getStatusCode().value()).isEqualTo(409);assertThat(reply.getBody().code()).isEqualTo("E1806");assertThat(reply.getHeaders().getFirst("Cache-Control")).contains("private","no-store");
    }
    @Test void rawUploadCsrfNeverParsesBodyParametersAndOtherRoutesKeepTheirExistingResolver() {
        var token=new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN","_csrf","server-token");
        var handler=new com.xksystem.logistics.upms.security.HeaderOnlyCsrfRequestHandler(request->request.getServletPath().endsWith("/evidence"));
        var raw=new org.springframework.mock.web.MockHttpServletRequest(){
            @Override public String getParameter(String name){throw new AssertionError("Raw photo must not parse form parameters");}
        };
        raw.setServletPath("/api/v1/tms/my-tasks/"+UUID.randomUUID()+"/evidence");
        assertThat(handler.resolveCsrfTokenValue(raw,token)).isNull();
        raw.addHeader("X-CSRF-TOKEN","client-token");assertThat(handler.resolveCsrfTokenValue(raw,token)).isEqualTo("client-token");
        var existing=new org.springframework.mock.web.MockHttpServletRequest();existing.setServletPath("/api/v1/auth/login");existing.addParameter("_csrf","existing-form-token");
        assertThat(handler.resolveCsrfTokenValue(existing,token)).isEqualTo("existing-form-token");
    }
}
