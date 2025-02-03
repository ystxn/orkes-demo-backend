package space.yong.orkes.services;

import static com.azure.ai.formrecognizer.documentanalysis.models.DocumentFieldType.CURRENCY;
import static com.azure.ai.formrecognizer.documentanalysis.models.DocumentFieldType.DATE;
import static com.azure.ai.formrecognizer.documentanalysis.models.DocumentFieldType.LIST;
import static com.azure.ai.formrecognizer.documentanalysis.models.DocumentFieldType.STRING;
import com.azure.ai.formrecognizer.documentanalysis.DocumentAnalysisClient;
import com.azure.ai.formrecognizer.documentanalysis.DocumentAnalysisClientBuilder;
import com.azure.ai.formrecognizer.documentanalysis.models.DocumentField;
import com.azure.ai.formrecognizer.documentanalysis.models.DocumentFieldType;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.util.BinaryData;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AzureAiService {
    @Value("${azure-doc-intel.endpoint}")
    private String endpoint;
    @Value("${azure-doc-intel.key}")
    private String key;
    private DocumentAnalysisClient client;

    public record InvoiceItem(String description, long quantity, double unitPrice) {}
    public record Invoice(String invoiceNumber, String vendor, LocalDate date, List<InvoiceItem> items, double total) {}

    @PostConstruct
    public void init() {
        client = new DocumentAnalysisClientBuilder()
            .credential(new AzureKeyCredential(key))
            .endpoint(endpoint)
            .buildClient();
    }

    @PostMapping("/infer-image")
    public Invoice inferImage(@RequestParam("file") MultipartFile file) throws IOException {
        var data = BinaryData.fromBytes(file.getBytes());
        var analyzeInvoicePoller = client.beginAnalyzeDocument("prebuilt-invoice", data);
        var analyzeInvoiceResult = analyzeInvoicePoller.getFinalResult();
        var analyzedInvoice = analyzeInvoiceResult.getDocuments().getFirst();

        Map<String, DocumentField> fields = analyzedInvoice.getFields();

        DocumentField vendorNameField = fields.get("VendorName");
        String vendorName = vendorNameField != null && STRING == vendorNameField.getType()
            ? vendorNameField.getValueAsString() : "";

        DocumentField invoiceIdField = fields.get("InvoiceId");
        String invoiceNumber = invoiceIdField != null && STRING == invoiceIdField.getType()
            ? invoiceIdField.getValueAsString() : "";

        DocumentField dateField = fields.get("InvoiceDate");
        LocalDate invoiceDate = dateField != null  && DATE == dateField.getType()
            ? dateField.getValueAsDate() : null;

        DocumentField invoiceTotalField = fields.get("InvoiceTotal");
        double invoiceTotal = invoiceTotalField != null && CURRENCY == invoiceTotalField.getType()
            ? invoiceTotalField.getValueAsCurrency().getAmount() : 0;

        DocumentField itemsField = fields.get("Items");
        List<InvoiceItem> items = (itemsField != null && LIST == itemsField.getType()) ?
            itemsField.getValueAsList().stream()
                .filter(item -> DocumentFieldType.MAP == item.getType())
                .map(DocumentField::getValueAsMap)
                .map(item -> {
                    String description = item.get("Description") != null ? item.get("Description").getValueAsString() : "";
                    long quantity = item.get("Quantity") != null ? item.get("Quantity").getValueAsDouble().longValue() : 1;
                    double unitPrice = 0;
                    DocumentField unitPriceField = item.get("UnitPrice");
                    if (unitPriceField != null && CURRENCY == unitPriceField.getType()) {
                        int multiplier = unitPriceField.getContent().contains("-") ? -1 : 1;
                        unitPrice = unitPriceField.getValueAsCurrency().getAmount() * multiplier;
                    }
                    return new InvoiceItem(description, quantity, unitPrice);
                })
                .toList()
            : new ArrayList<>();

        return new Invoice(invoiceNumber, vendorName, invoiceDate, items, invoiceTotal);
    }
}
