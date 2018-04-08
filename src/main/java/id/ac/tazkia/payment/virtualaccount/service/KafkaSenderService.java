package id.ac.tazkia.payment.virtualaccount.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.ac.tazkia.payment.virtualaccount.dao.TagihanDao;
import id.ac.tazkia.payment.virtualaccount.dao.VirtualAccountDao;
import id.ac.tazkia.payment.virtualaccount.dto.*;
import id.ac.tazkia.payment.virtualaccount.entity.*;
import id.ac.tazkia.payment.virtualaccount.helper.VirtualAccountNumberGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

@Service @Transactional
public class KafkaSenderService {
    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaSenderService.class);
    private static final String FORMAT_DATETIME = "yyyy-MM-dd HH:mm:ss";

    private static final Integer NOTIFICATION_BATCH_SIZE = 50;

    @Value("${kafka.topic.notification.request}") private String kafkaTopicNotificationRequest;
    @Value("${kafka.topic.va.request}") private String kafkaTopicVaRequest;
    @Value("${kafka.topic.debitur.response}") private String kafkaTopicDebiturResponse;
    @Value("${kafka.topic.tagihan.response}") private String kafkaTopicTagihanResponse;
    @Value("${kafka.topic.tagihan.payment}") private String kafkaTopicPembayaranTagihan;

    @Value("${notifikasi.delay.menit}") private Integer delayNotifikasi;

    @Value("${notifikasi.konfigurasi.tagihan}") private String konfigurasiTagihan;
    @Value("${notifikasi.konfigurasi.pembayaran}") private String konfigurasiPembayaran;
    @Value("${notifikasi.contactinfo}") private String contactinfo;
    @Value("${notifikasi.contactinfoFull}") private String contactinfoFull;
    @Value("${notifikasi.email.finance}") private String financeEmail;
    @Value("${notifikasi.email.finance.send}") private Boolean sendFinanceEmail;
    @Value("${notifikasi.email.it}") private String itEmail;
    @Value("${notifikasi.email.it.send}") private Boolean sendItEmail;

    @Autowired private ObjectMapper objectMapper;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired private VirtualAccountDao virtualAccountDao;
    @Autowired private TagihanDao tagihanDao;

    @Scheduled(fixedDelay = 1000)
    public void prosesVaBaru() {
        processVa(VaStatus.CREATE);
    }

    @Scheduled(fixedDelay = 1000)
    public void prosesVaUpdate() {
        processVa(VaStatus.UPDATE);
    }

    @Scheduled(fixedDelay = 1000)
    public void prosesVaDelete() {
        processVa(VaStatus.DELETE);
    }

    @Scheduled(fixedDelay = 60 * 1000)
    public void prosesNotifikasiTagihan() {
        for(Tagihan tagihan : tagihanDao.findByStatusNotifikasi(StatusNotifikasi.BELUM_TERKIRIM,
                PageRequest.of(0, NOTIFICATION_BATCH_SIZE)).getContent()) {
            // tunggu aktivasi VA dulu selama 60 menit
            if (LocalDateTime.now().isBefore(
                    tagihan.getUpdatedAt().plusMinutes(delayNotifikasi))) {
                continue;
            }

            // tidak ada VA, tidak usah kirim notifikasi
            Iterator<VirtualAccount> daftarVa = virtualAccountDao.findByTagihan(tagihan).iterator();
            Boolean adaVaAktif = false;
            while (daftarVa.hasNext()) {
                VirtualAccount va = daftarVa.next();
                if (VaStatus.AKTIF.equals(va.getVaStatus())) {
                    adaVaAktif = true;
                    break;
                }
            }

            if (!adaVaAktif) {
                continue;
            }

            sendNotifikasiTagihan(tagihan);
        }
    }

    public void sendNotifikasiTagihan(Tagihan tagihan) {
        try {
            String email = tagihan.getDebitur().getEmail();
            String hp = tagihan.getDebitur().getNoHp();

            Map<String, Object> notifikasi = new LinkedHashMap<>();
            notifikasi.put("email", email);
            notifikasi.put("mobile", hp);
            notifikasi.put("konfigurasi", konfigurasiTagihan);


            StringBuilder rekening = new StringBuilder("");
            StringBuilder rekeningFull = new StringBuilder("<ul>");

            for (VirtualAccount va : virtualAccountDao.findByTagihan(tagihan)) {
                if (!VaStatus.AKTIF.equals(va.getVaStatus())) {
                    continue;
                }
                if (rekening.length() > 0) {
                    rekening.append("/");
                }
                rekening.append(va.getBank().getNama() + " " + va.getNomor());
                rekeningFull.append("<li>" + va.getBank().getNama() + " " + va.getNomor() + "</li>");
            }
            rekeningFull.append("</ul>");


            NotifikasiTagihanRequest requestData = NotifikasiTagihanRequest.builder()
                    .jumlah(tagihan.getNilaiTagihan())
                    .keterangan(tagihan.getJenisTagihan().getNama())
                    .nama(tagihan.getDebitur().getNama())
                    .email(tagihan.getDebitur().getEmail())
                    .noHp(tagihan.getDebitur().getNoHp())
                    .nomorTagihan(tagihan.getNomor())
                    .rekening(rekening.toString())
                    .rekeningFull(rekeningFull.toString())
                    .tanggalTagihan(tagihan.getTanggalTagihan().format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .contactinfo(contactinfo)
                    .contactinfoFull(contactinfoFull)
                    .build();

            notifikasi.put("data", requestData);
            kafkaTemplate.send(kafkaTopicNotificationRequest, objectMapper.writeValueAsString(notifikasi));
            tagihan.setStatusNotifikasi(StatusNotifikasi.SUDAH_TERKIRIM);
            tagihanDao.save(tagihan);
        } catch (Exception err) {
            LOGGER.warn(err.getMessage(), err);
        }
    }

    public void sendNotifikasiPembayaran(Pembayaran pembayaran) {
        sendPembayaranTagihan(pembayaran);
        try {
            // notifikasi untuk debitur
            sendNotifikasiPembayaran(pembayaran.getTagihan().getDebitur().getEmail(),
                    pembayaran.getTagihan().getDebitur().getNoHp(), pembayaran);

            if(sendFinanceEmail) {
                sendNotifikasiPembayaran(financeEmail, null, pembayaran);
            }

            if (sendItEmail) {
                sendNotifikasiPembayaran(itEmail, null, pembayaran);
            }

        } catch (Exception err) {
            LOGGER.warn(err.getMessage(), err);
        }
    }

    private void sendNotifikasiPembayaran(String email, String mobile, Pembayaran pembayaran) throws JsonProcessingException {
        NotifikasiPembayaranRequest request = NotifikasiPembayaranRequest.builder()
                .contactinfo(contactinfo)
                .contactinfoFull(contactinfoFull)
                .keterangan(pembayaran.getTagihan().getJenisTagihan().getNama())
                .nomorTagihan(pembayaran.getTagihan().getNomor())
                .nama(pembayaran.getTagihan().getDebitur().getNama())
                .noHp(pembayaran.getTagihan().getDebitur().getNoHp())
                .tanggalTagihan(pembayaran.getTagihan().getTanggalTagihan().format(DateTimeFormatter.ISO_LOCAL_DATE))
                .nilaiPembayaran(pembayaran.getJumlah())
                .nilaiTagihan(pembayaran.getTagihan().getNilaiTagihan())
                .rekening(pembayaran.getBank().getNama())
                .waktu(pembayaran.getWaktuTransaksi().format(DateTimeFormatter.ofPattern(FORMAT_DATETIME)))
                .referensi(pembayaran.getReferensi())
                .build();
        Map<String, Object> notifikasi = new LinkedHashMap<>();
        notifikasi.put("email", email);
        if (mobile != null) {
            notifikasi.put("mobile", mobile);
        }
        notifikasi.put("konfigurasi", konfigurasiPembayaran);
        notifikasi.put("data", request);
        kafkaTemplate.send(kafkaTopicNotificationRequest, objectMapper.writeValueAsString(notifikasi));
    }

    public void sendTagihanResponse(TagihanResponse tagihanResponse) {
        try {
            kafkaTemplate.send(kafkaTopicTagihanResponse, objectMapper.writeValueAsString(tagihanResponse));
        } catch (Exception err) {
            LOGGER.warn(err.getMessage(), err);
        }
    }

    public void sendDebiturResponse(Map<String, Object> data) {
        try {
            kafkaTemplate.send(kafkaTopicDebiturResponse, objectMapper.writeValueAsString(data));
        } catch (Exception err) {
            LOGGER.warn(err.getMessage(), err);
        }
    }

    public void sendPembayaranTagihan(Pembayaran p) {
        PembayaranTagihan pt = PembayaranTagihan.builder()
                .bank(p.getBank().getId())
                .jenisTagihan(p.getTagihan().getJenisTagihan().getKode())
                .nomorTagihan(p.getTagihan().getNomor())
                .nomorDebitur(p.getTagihan().getDebitur().getNomorDebitur())
                .namaDebitur(p.getTagihan().getDebitur().getNama())
                .keteranganTagihan(p.getTagihan().getKeterangan())
                .statusTagihan(p.getTagihan().getStatusTagihan().toString())
                .nilaiTagihan(p.getTagihan().getNilaiTagihan())
                .nilaiPembayaran(p.getJumlah())
                .nilaiAkumulasiPembayaran(p.getTagihan().getJumlahPembayaran())
                .referensiPembayaran(p.getReferensi())
                .waktuPembayaran(p.getWaktuTransaksi().format(DateTimeFormatter.ofPattern(FORMAT_DATETIME)))
                .tanggalTagihan(p.getTagihan().getTanggalTagihan().format(DateTimeFormatter.ISO_LOCAL_DATE))
                .tanggalJatuhTempo(p.getTagihan().getTanggalJatuhTempo().format(DateTimeFormatter.ISO_LOCAL_DATE))
                .build();

        try {
            kafkaTemplate.send(kafkaTopicPembayaranTagihan, objectMapper.writeValueAsString(pt));
        } catch (Exception err) {
            LOGGER.warn(err.getMessage(), err);
        }
    }

    private void processVa(VaStatus status) {
        Iterator<VirtualAccount> daftarVa = virtualAccountDao.findByVaStatus(status).iterator();
        if (daftarVa.hasNext()) {
            VirtualAccount va = daftarVa.next();
            try {
                // VA update dan delete harus ada nomor VAnya
                if (!VaStatus.CREATE.equals(status) && !StringUtils.hasText(va.getNomor())) {
                    LOGGER.warn("VA Request {} untuk no tagihan {} tidak ada nomer VA-nya ", status, va.getTagihan().getNomor());
                    return;
                }

                VaRequest vaRequest = createRequest(va, status);
                String json = objectMapper.writeValueAsString(vaRequest);
                LOGGER.debug("VA Request : {}", json);
                kafkaTemplate.send(kafkaTopicVaRequest, json);
                va.setVaStatus(VaStatus.SEDANG_PROSES);
                virtualAccountDao.save(va);
            } catch (Exception err) {
                LOGGER.warn(err.getMessage(), err);
            }
        }
    }

    private VaRequest createRequest(VirtualAccount va, VaStatus requestType) {
        VaRequest vaRequest
                = VaRequest.builder()
                .accountType(va.getTagihan().getJenisTagihan().getTipePembayaran())
                .requestType(requestType)
                .amount(va.getTagihan().getNilaiTagihan().subtract(va.getTagihan().getJumlahPembayaran()))
                .description(va.getTagihan().getKeterangan())
                .email(va.getTagihan().getDebitur().getEmail())
                .phone(va.getTagihan().getDebitur().getNoHp())
                .expireDate(va.getTagihan().getTanggalJatuhTempo().format(DateTimeFormatter.ISO_LOCAL_DATE))
                .expireDate(va.getTagihan().getTanggalJatuhTempo().format(DateTimeFormatter.ISO_LOCAL_DATE))
                .invoiceNumber(va.getTagihan().getNomor())
                .name(va.getTagihan().getDebitur().getNama())
                .bankId(va.getBank().getId())
                .build();

        if (VaStatus.CREATE.equals(requestType)) {
            vaRequest.setAccountNumber(VirtualAccountNumberGenerator
                    .generateVirtualAccountNumber(
                            va.getTagihan().getJenisTagihan().getKode()+
                                    va.getTagihan().getDebitur().getNomorDebitur(),
                            va.getBank().getJumlahDigitVirtualAccount()));
        } else {
            vaRequest.setAccountNumber(va.getNomor().substring(va.getBank().getJumlahDigitPrefix()));
        }

        return vaRequest;
    }
}