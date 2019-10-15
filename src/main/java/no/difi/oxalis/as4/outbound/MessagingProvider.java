package no.difi.oxalis.as4.outbound;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import no.difi.oxalis.api.outbound.TransmissionRequest;
import no.difi.oxalis.as4.api.MessageIdGenerator;
import no.difi.oxalis.as4.lang.OxalisAs4TransmissionException;
import no.difi.oxalis.as4.util.CompressionUtil;
import no.difi.oxalis.as4.util.Constants;
import no.difi.oxalis.as4.util.PeppolConfiguration;
import no.difi.oxalis.commons.security.CertificateUtils;
import org.apache.cxf.attachment.AttachmentUtil;
import org.apache.cxf.binding.soap.SoapHeader;
import org.apache.cxf.jaxb.JAXBDataBinding;
import org.apache.cxf.message.Attachment;
import org.oasis_open.docs.ebxml_msg.ebms.v3_0.ns.core._200704.*;

import javax.xml.bind.JAXBException;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static no.difi.oxalis.as4.util.Constants.TEST_ACTION;
import static no.difi.oxalis.as4.util.Constants.TEST_SERVICE;

public class MessagingProvider {

    private final X509Certificate certificate;
    private final CompressionUtil compressionUtil;
    private final MessageIdGenerator messageIdGenerator;
    private final PeppolConfiguration defaultOutboundConfiguration;


    @Inject
    public MessagingProvider(X509Certificate certificate, CompressionUtil compressionUtil, MessageIdGenerator messageIdGenerator, PeppolConfiguration defaultOutboundConfiguration) {
        this.certificate = certificate;
        this.compressionUtil = compressionUtil;
        this.messageIdGenerator = messageIdGenerator;
        this.defaultOutboundConfiguration = defaultOutboundConfiguration;
    }

    public SoapHeader createMessagingHeader(TransmissionRequest request, Collection<Attachment> attachments) throws OxalisAs4TransmissionException {

        UserMessage userMessage = UserMessage.builder()
                .withMessageInfo(createMessageInfo(request))
                .withPartyInfo(createPartyInfo(request))
                .withCollaborationInfo(createCollaborationInfo(request))
                .withMessageProperties(createMessageProperties(request))
                .withPayloadInfo(createPayloadInfo(request, attachments))
                .build();


        Messaging messaging = Messaging.builder()
                .addUserMessage(userMessage)
                .build();

        try {
            return new SoapHeader(
                    Constants.MESSAGING_QNAME,
                    messaging,
                    new JAXBDataBinding(Messaging.class),
                    true);
        }catch (JAXBException e){
            throw new OxalisAs4TransmissionException("Unable to marshal AS4 header", e);
        }
    }





    private PayloadInfo createPayloadInfo(TransmissionRequest request, Collection<Attachment> attachments) {

        ArrayList<PartInfo> partInfos = Lists.newArrayList();
        for(Attachment attachment : attachments){

            PartProperties.Builder partProperties = PartProperties.builder();

            String cid = "cid:" + AttachmentUtil.cleanContentId(attachment.getId());

            iteratorToStream(attachment.getHeaderNames())
                    .filter(header -> !"Content-ID".equals(header))
                    .map(header -> Property.builder().withName(header).withValue(attachment.getHeader(header)).build())
                    .forEach(partProperties::addProperty);

            if (request instanceof As4TransmissionRequest) {
                As4TransmissionRequest as4TransmissionRequest = (As4TransmissionRequest) request;
                if(null != as4TransmissionRequest.getPayloadCharset()){
                    partProperties.addProperty(
                            Property.builder()
                                    .withName("CharacterSet")
                                    .withValue( as4TransmissionRequest.getPayloadCharset().name().toLowerCase() )
                                    .build()
                    );
                }
            }

            PartInfo partInfo = PartInfo.builder()
                    .withHref(cid)
                    .withPartProperties(partProperties.build())
                    .build();
            partInfos.add(partInfo);
        }

        return PayloadInfo.builder()
                .withPartInfo(partInfos)
                .build();
    }







    private MessageProperties createMessageProperties(TransmissionRequest request) {
        Map<String, String> properties = new HashMap<>();

        if (request instanceof As4TransmissionRequest) {
            As4TransmissionRequest as4TransmissionRequest = (As4TransmissionRequest) request;
            if (as4TransmissionRequest.getMessageProperties() != null) {
                properties.putAll(as4TransmissionRequest.getMessageProperties());
            }
        }

        if (!properties.containsKey("originalSender")) {
            properties.put("originalSender", request.getHeader().getSender().toString());
        }

        if (!properties.containsKey("finalRecipient")) {
            properties.put("finalRecipient", request.getHeader().getReceiver().toString());
        }

        return MessageProperties.builder()
                .withProperty(properties.entrySet().stream()
                        .map(p -> Property.builder()
                                .withName(p.getKey())
                                .withValue(p.getValue())
                                .build())
                        .collect(Collectors.toList())
                )
                .build();
    }

    private PartyInfo createPartyInfo(TransmissionRequest request) {

        String fromName = CertificateUtils.extractCommonName(certificate);
        String toName = CertificateUtils.extractCommonName(request.getEndpoint().getCertificate());

        PeppolConfiguration outboundConfiguration = request.getTag() instanceof PeppolConfiguration ?
                (PeppolConfiguration) request.getTag() : defaultOutboundConfiguration;

        return PartyInfo.builder()
                .withFrom(From.builder()
                        .withPartyId(PartyId.builder()
                                .withType(outboundConfiguration.getPartyIDType())
                                .withValue(fromName)
                                .build())
                        .withRole(outboundConfiguration.getFromRole())
                        .build())
                .withTo(To.builder()
                        .withPartyId(PartyId.builder()
                                .withType(outboundConfiguration.getPartyIDType())
                                .withValue(toName)
                                .build())
                        .withRole(outboundConfiguration.getToRole())
                        .build()
                ).build();
    }

    private CollaborationInfo createCollaborationInfo(TransmissionRequest request) {

        PeppolConfiguration outboundConfiguration = request.getTag() instanceof PeppolConfiguration ?
                (PeppolConfiguration) request.getTag() : defaultOutboundConfiguration;

        CollaborationInfo.Builder cib = CollaborationInfo.builder()
                .withConversationId(getConversationId(request))
                .withAction(request.getHeader().getDocumentType().toString())
                .withService(Service.builder()
                        .withType(outboundConfiguration.getServiceType())
                        .withValue(request.getHeader().getProcess().getIdentifier())
                        .build()
                );

        if (request instanceof As4TransmissionRequest && ((As4TransmissionRequest)request).isPing()) {
            cib = cib.withAction(TEST_ACTION)
                    .withService(Service.builder()
                            .withValue(TEST_SERVICE)
                            .build());
        }



        if (defaultOutboundConfiguration.getAgreementRef() != null) {
            cib = cib.withAgreementRef(AgreementRef.builder()
                    .withValue(defaultOutboundConfiguration.getAgreementRef())
                    .build()
            );
        }

        return cib.build();
    }

    private MessageInfo createMessageInfo(TransmissionRequest request) {
        GregorianCalendar gcal = GregorianCalendar.from(LocalDateTime.now().atZone(ZoneId.systemDefault()));
        XMLGregorianCalendar xmlDate;
        try {
            xmlDate = DatatypeFactory.newInstance().newXMLGregorianCalendar(gcal);
        } catch (DatatypeConfigurationException e) {
            throw new RuntimeException("Error getting xml date", e);
        }

        MessageInfo.Builder builder = MessageInfo.builder()
                .withMessageId(getMessageId(request))
                .withTimestamp(xmlDate);

        if( request instanceof As4TransmissionRequest){
            As4TransmissionRequest as4TransmissionRequest = (As4TransmissionRequest) request;
            if( as4TransmissionRequest.getRefToMessageId() != null ){
                builder.withRefToMessageId( as4TransmissionRequest.getRefToMessageId() );
            }
        }

        return builder.build();
    }

    private String getMessageId(TransmissionRequest request) {
        String messageId = null;

        if (request instanceof As4TransmissionRequest) {
            As4TransmissionRequest as4TransmissionRequest = (As4TransmissionRequest) request;
            messageId = as4TransmissionRequest.getMessageId();
        }

        return messageId != null ? messageId : newId();
    }

    private String getConversationId(TransmissionRequest request) {
        String conversationId = null;

        if (request instanceof As4TransmissionRequest) {
            As4TransmissionRequest as4TransmissionRequest = (As4TransmissionRequest) request;
            conversationId = as4TransmissionRequest.getConversationId();
        }

        return conversationId != null ? conversationId : newId();
    }

    private String newId() {
        return messageIdGenerator.generate();
    }


    private <T> Stream<T> iteratorToStream(Iterator<T> iterator){
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED),
                false);
    }
}