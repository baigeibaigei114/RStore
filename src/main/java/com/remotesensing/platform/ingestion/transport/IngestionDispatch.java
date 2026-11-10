package com.remotesensing.platform.ingestion.transport;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.ingestion.workflow.IngestionWorkService;
import com.remotesensing.platform.ingestion.workflow.IngestionJobWorkService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Dedicated UUID publisher. Never enters the RS_TASK template/failure handler. */
@Component
public class IngestionDispatch {
    public static final String EXCHANGE="rs.ingestion.exchange", QUEUE="rs.ingestion.queue", KEY="rs.ingestion.work";
    private final IngestionStore db;
    private final IngestionProperties config;
    private final IngestionWorkService works;
    private final RabbitTemplate rabbit;
    private final IngestionJobWorkService jobs;
    public IngestionDispatch(IngestionStore db,IngestionProperties config,IngestionWorkService works,ConnectionFactory connection,IngestionJobWorkService jobs) {
        this.db=db;this.config=config;this.works=works;this.jobs=jobs;
        rabbit=new RabbitTemplate(connection);rabbit.setMandatory(true);
        rabbit.setConfirmCallback((data,ack,cause) -> {if(data!=null) acknowledge(data.getId(),ack);});
        rabbit.setReturnsCallback(returned -> acknowledge(returned.getMessage().getMessageProperties().getCorrelationId(),false));
    }
    private void acknowledge(String correlation,boolean ack) {
        if(correlation==null || !correlation.matches("ingest:[0-9a-f-]{36}:[1-5]")) return;
        String[] fields=correlation.split(":");
        db.update("UPDATE ingest_outbox SET status=?,sent_at=CASE WHEN ? THEN clock_timestamp() ELSE sent_at END,"
                + "next_send_at=clock_timestamp()+((power(2,send_count)) * interval '1 second'),updated_at=clock_timestamp()"
                + " WHERE id=? AND status='SENDING' AND send_count=?",ack?"SENT":"PENDING",ack,UUID.fromString(fields[1]),Integer.parseInt(fields[2]));
    }
    private ObjectNode begin(UUID id) {
        return db.transaction(() -> {
            ObjectNode row=db.one("SELECT * FROM ingest_outbox WHERE id=? AND dispatch_mode='MQ' AND kind IN ('INSPECT','PREFLIGHT','STANDARDIZE')"
                    + " AND ((status='PENDING' AND next_send_at<=clock_timestamp()) OR (status='SENDING' AND sending_until<=clock_timestamp())) FOR UPDATE",id);
            if(row==null) return null;
            if(row.path("send_count").asInt()>=row.path("max_sends").asInt()) {
                db.update("UPDATE ingest_outbox SET status='FAILED',updated_at=clock_timestamp(),last_error='{\"reason\":\"DISPATCH_FAILED\"}'::jsonb WHERE id=?",id);
                return row.put("exhausted",true);
            }
            db.update("UPDATE ingest_outbox SET status='SENDING',send_count=send_count+1,sending_until=clock_timestamp()+interval '30 seconds',updated_at=clock_timestamp() WHERE id=?",id);
            row.put("send_count",row.path("send_count").asInt()+1);return row;
        });
    }
    @Scheduled(fixedDelay=2000)
    public void sendPending() {
        if(!config.isEnabled()) return;
        for(ObjectNode candidate:db.rows("SELECT id FROM ingest_outbox WHERE dispatch_mode='MQ' AND kind IN ('INSPECT','PREFLIGHT','STANDARDIZE') AND"
                + " ((status='PENDING' AND next_send_at<=clock_timestamp()) OR (status='SENDING' AND sending_until<=clock_timestamp())) ORDER BY created_at LIMIT 10")) {
            ObjectNode row=begin(IngestionStore.id(candidate));if(row==null)continue;
            if(row.path("exhausted").asBoolean()) {
                if("STANDARDIZE".equals(row.path("kind").asText()))jobs.failQueued(IngestionStore.id(row),false,"DISPATCH_FAILED");
                else works.failQueued(IngestionStore.id(row),"DISPATCH_FAILED");continue;
            }
            String correlation="ingest:"+row.path("id").asText()+":"+row.path("send_count").asInt();
            MessageProperties properties=new MessageProperties();properties.setContentType("application/json");
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);properties.setCorrelationId(correlation);
            Message message=new Message(row.path("payload").toString().getBytes(StandardCharsets.UTF_8),properties);
            try {rabbit.send(EXCHANGE,KEY,message,new CorrelationData(correlation));}
            catch(org.springframework.amqp.AmqpException error) {acknowledge(correlation,false);}
        }
    }
    @Configuration
    @ConditionalOnProperty(prefix="ingestion",name="enabled",havingValue="true")
    public static class Queues {
        @Bean public Declarables ingestionQueues() {
            DirectExchange exchange=new DirectExchange(EXCHANGE,true,false), dead=new DirectExchange("rs.ingestion.dlx",true,false);
            Queue queue=QueueBuilder.durable(QUEUE).quorum().ttl(1800000).maxLength(10)
                    .overflow(QueueBuilder.Overflow.rejectPublish).deadLetterExchange("rs.ingestion.dlx").deadLetterRoutingKey("rs.ingestion.dead")
                    .withArgument("x-delivery-limit",5).build();
            Queue deadQueue=QueueBuilder.durable("rs.ingestion.dead.queue").quorum().maxLength(100).build();
            return new Declarables(exchange,dead,queue,deadQueue,BindingBuilder.bind(queue).to(exchange).with(KEY),BindingBuilder.bind(deadQueue).to(dead).with("rs.ingestion.dead"));
        }
    }
    @jakarta.annotation.PreDestroy public void close() {rabbit.destroy();}
}
