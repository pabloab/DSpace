/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.external.provider.impl;

import static java.util.Collections.emptyList;
import static java.util.Comparator.comparing;
import static java.util.Comparator.reverseOrder;
import static java.util.Optional.ofNullable;
import static org.apache.commons.collections4.ListUtils.partition;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.dspace.content.Item.ANY;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.dspace.app.orcid.client.OrcidClient;
import org.dspace.app.orcid.client.OrcidConfiguration;
import org.dspace.app.orcid.model.OrcidTokenResponseDTO;
import org.dspace.app.orcid.model.OrcidWorkFieldMapping;
import org.dspace.app.orcid.service.OrcidSynchronizationService;
import org.dspace.content.Item;
import org.dspace.content.MetadataFieldName;
import org.dspace.content.dto.MetadataValueDTO;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.external.model.ExternalDataObject;
import org.dspace.external.provider.AbstractExternalDataProvider;
import org.dspace.external.provider.ExternalDataProvider;
import org.orcid.jaxb.model.common.ContributorRole;
import org.orcid.jaxb.model.common.WorkType;
import org.orcid.jaxb.model.v3.release.common.Contributor;
import org.orcid.jaxb.model.v3.release.common.ContributorAttributes;
import org.orcid.jaxb.model.v3.release.common.PublicationDate;
import org.orcid.jaxb.model.v3.release.common.Subtitle;
import org.orcid.jaxb.model.v3.release.common.Title;
import org.orcid.jaxb.model.v3.release.record.ExternalIDs;
import org.orcid.jaxb.model.v3.release.record.SourceAware;
import org.orcid.jaxb.model.v3.release.record.Work;
import org.orcid.jaxb.model.v3.release.record.WorkBulk;
import org.orcid.jaxb.model.v3.release.record.WorkContributors;
import org.orcid.jaxb.model.v3.release.record.WorkTitle;
import org.orcid.jaxb.model.v3.release.record.summary.WorkGroup;
import org.orcid.jaxb.model.v3.release.record.summary.WorkSummary;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Implementation of {@link ExternalDataProvider} that search for all the works
 * of the profile with the given orcid id that hava a source other than
 * DSpaceCris. The id of the external data objects returned by the methods of
 * this class is the concatenation of the orcid id and the put code associated
 * with the publication, separated by ::
 *
 * @author Luca Giamminonni (luca.giamminonni at 4science.it)
 *
 */
public class OrcidPublicationDataProvider extends AbstractExternalDataProvider {

    private final static int MAX_PUT_CODES_SIZE = 100;

    @Autowired
    private OrcidClient orcidClient;

    @Autowired
    private OrcidConfiguration orcidConfiguration;

    @Autowired
    private ItemService itemService;

    @Autowired
    private OrcidSynchronizationService orcidSynchronizationService;

    private OrcidWorkFieldMapping fieldMapping;

    private String sourceIdentifier;

    private String readPublicAccessToken;

    @Override
    public Optional<ExternalDataObject> getExternalDataObject(String id) {

        if (isInvalidIdentifier(id)) {
            throw new IllegalArgumentException("Invalid identifier '" + id + "', expected <orcid-id>::<put-code>");
        }

        String[] idSections = id.split("::");
        String orcid = idSections[0];
        String putCode = idSections[1];
        String accessToken = getAccessToken(orcid);

        return orcidClient.getObject(accessToken, orcid, putCode, Work.class)
            .filter(work -> hasDifferentSourceClientId(work))
            .filter(work -> work.getPutCode() != null)
            .map(work -> convertToExternalDataObject(orcid, work));
    }

    @Override
    public List<ExternalDataObject> searchExternalDataObjects(String orcid, int start, int limit) {
        String accessToken = getAccessToken(orcid);
        return findWorks(accessToken, orcid, start, limit).stream()
            .map(work -> convertToExternalDataObject(orcid, work))
            .collect(Collectors.toList());
    }

    private boolean isInvalidIdentifier(String id) {
        return StringUtils.isBlank(id) || id.split("::").length != 2;
    }

    private String getAccessToken(String orcid) {
        return orcidSynchronizationService.findProfilesByOrcid(new Context(), orcid).stream()
            .map(item -> getAccessToken(item))
            .flatMap(Optional::stream)
            .findFirst()
            .orElseGet(() -> getReadPublicAccessToken());
    }

    private String getReadPublicAccessToken() {
        if (readPublicAccessToken != null) {
            return readPublicAccessToken;
        }

        OrcidTokenResponseDTO accessTokenResponse = orcidClient.getReadPublicAccessToken();
        readPublicAccessToken = accessTokenResponse.getAccessToken();

        return readPublicAccessToken;
    }

    private Optional<String> getAccessToken(Item item) {
        return ofNullable(itemService.getMetadataFirstValue(item, "cris", "orcid", "access-token", ANY));
    }

    private List<Work> findWorks(String accessToken, String orcid, int start, int limit) {
        List<WorkSummary> workSummaries = findWorkSummaries(accessToken, orcid, start, limit);
        return findWorks(accessToken, orcid, workSummaries);
    }

    private List<WorkSummary> findWorkSummaries(String accessToken, String orcid, int start, int limit) {
        return orcidClient.getWorks(accessToken, orcid).getWorkGroup().stream()
            .filter(workGroup -> allWorkSummariesHaveDifferentSourceClientId(workGroup))
            .map(workGroup -> getPreferredWorkSummary(workGroup))
            .flatMap(Optional::stream)
            .skip(start)
            .limit(limit > 0 ? limit : Long.MAX_VALUE)
            .collect(Collectors.toList());
    }

    private List<Work> findWorks(String accessToken, String orcid, List<WorkSummary> workSummaries) {

        List<String> workPutCodes = getPutCodes(workSummaries);

        if (CollectionUtils.isEmpty(workPutCodes)) {
            return emptyList();
        }

        if (workPutCodes.size() == 1) {
            String putCode = workPutCodes.get(0);
            return orcidClient.getObject(accessToken, orcid, putCode, Work.class)
                .stream().collect(Collectors.toList());
        }

        return partition(workPutCodes, MAX_PUT_CODES_SIZE).stream()
            .map(putCodes -> orcidClient.getWorkBulk(accessToken, orcid, putCodes))
            .flatMap(workBulk -> getWorks(workBulk).stream())
            .collect(Collectors.toList());
    }

    private List<Work> getWorks(WorkBulk workBulk) {
        return workBulk.getBulk().stream()
            .filter(bulkElement -> (bulkElement instanceof Work))
            .map(bulkElement -> ((Work) bulkElement))
            .collect(Collectors.toList());

    }

    private List<String> getPutCodes(List<WorkSummary> workSummaries) {
        return workSummaries.stream()
            .map(WorkSummary::getPutCode)
            .map(String::valueOf)
            .collect(Collectors.toList());
    }

    private Optional<WorkSummary> getPreferredWorkSummary(WorkGroup workGroup) {
        return workGroup.getWorkSummary().stream()
            .filter(work -> work.getPutCode() != null)
            .filter(work -> NumberUtils.isCreatable(work.getDisplayIndex()))
            .sorted(comparing(work -> Integer.valueOf(work.getDisplayIndex()), reverseOrder()))
            .findFirst();
    }

    private ExternalDataObject convertToExternalDataObject(String orcid, Work work) {
        ExternalDataObject externalDataObject = new ExternalDataObject(sourceIdentifier);
        externalDataObject.setId(orcid + "::" + work.getPutCode().toString());

        String title = getWorkTitle(work);
        externalDataObject.setDisplayValue(title);
        externalDataObject.setValue(title);

        addMetadataValue(externalDataObject, fieldMapping.getTitleField(), () -> title);
        addMetadataValue(externalDataObject, fieldMapping.getTypeField(), () -> getWorkType(work));
        addMetadataValue(externalDataObject, fieldMapping.getPublicationDateField(), () -> getPublicationDate(work));
        addMetadataValue(externalDataObject, fieldMapping.getJournalTitleField(), () -> getJournalTitle(work));
        addMetadataValue(externalDataObject, fieldMapping.getSubTitleField(), () -> getSubTitleField(work));
        addMetadataValue(externalDataObject, fieldMapping.getShortDescriptionField(), () -> getDescription(work));
        addMetadataValue(externalDataObject, fieldMapping.getLanguageField(), () -> getLanguage(work));

        for (String contributorField : fieldMapping.getContributorFields().keySet()) {
            ContributorRole role = fieldMapping.getContributorFields().get(contributorField);
            addMetadataValues(externalDataObject, contributorField, () -> getContributors(work, role));
        }

        for (String externalIdField : fieldMapping.getExternalIdentifierFields().keySet()) {
            String type = fieldMapping.getExternalIdentifierFields().get(externalIdField);
            addMetadataValues(externalDataObject, externalIdField, () -> getExternalIds(work, type));
        }

        return externalDataObject;
    }

    private boolean allWorkSummariesHaveDifferentSourceClientId(WorkGroup workGroup) {
        return workGroup.getWorkSummary().stream()
            .allMatch(this::hasDifferentSourceClientId);
    }

    @SuppressWarnings("deprecation")
    private boolean hasDifferentSourceClientId(SourceAware sourceAware) {
        return Optional.ofNullable(sourceAware.getSource())
            .map(source -> source.getSourceClientId())
            .map(sourceClientId -> sourceClientId.getPath())
            .map(clientId -> !StringUtils.equals(orcidConfiguration.getClientId(), clientId))
            .orElse(true);
    }

    private void addMetadataValues(ExternalDataObject externalData, String metadata, Supplier<List<String>> values) {

        if (StringUtils.isBlank(metadata)) {
            return;
        }

        MetadataFieldName field = new MetadataFieldName(metadata);
        for (String value : values.get()) {
            externalData.addMetadata(new MetadataValueDTO(field.SCHEMA, field.ELEMENT, field.QUALIFIER, null, value));
        }
    }

    private void addMetadataValue(ExternalDataObject externalData, String metadata, Supplier<String> valueSupplier) {
        addMetadataValues(externalData, metadata, () -> {
            String value = valueSupplier.get();
            return isNotBlank(value) ? List.of(value) : emptyList();
        });
    }

    private String getWorkTitle(Work work) {
        WorkTitle workTitle = work.getWorkTitle();
        if (workTitle == null) {
            return null;
        }
        Title title = workTitle.getTitle();
        return title != null ? title.getContent() : null;
    }

    private String getWorkType(Work work) {
        WorkType workType = work.getWorkType();
        return workType != null ? fieldMapping.convertType(workType.value()) : null;
    }

    private String getPublicationDate(Work work) {
        PublicationDate publicationDate = work.getPublicationDate();
        if (publicationDate == null) {
            return null;
        }

        StringBuilder builder = new StringBuilder(publicationDate.getYear().getValue());
        if (publicationDate.getMonth() != null) {
            builder.append("-");
            builder.append(publicationDate.getMonth().getValue());
        }

        if (publicationDate.getDay() != null) {
            builder.append("-");
            builder.append(publicationDate.getDay().getValue());
        }

        return builder.toString();
    }

    private String getJournalTitle(Work work) {
        Title journalTitle = work.getJournalTitle();
        return journalTitle != null ? journalTitle.getContent() : null;
    }

    private String getSubTitleField(Work work) {
        WorkTitle workTitle = work.getWorkTitle();
        if (workTitle == null) {
            return null;
        }
        Subtitle subTitle = workTitle.getSubtitle();
        return subTitle != null ? subTitle.getContent() : null;
    }

    private String getDescription(Work work) {
        return work.getShortDescription();
    }

    private String getLanguage(Work work) {
        return work.getLanguageCode() != null ? fieldMapping.convertLanguage(work.getLanguageCode()) : null;
    }

    private List<String> getContributors(Work work, ContributorRole role) {
        WorkContributors workContributors = work.getWorkContributors();
        if (workContributors == null) {
            return emptyList();
        }

        return workContributors.getContributor().stream()
            .filter(contributor -> hasRole(contributor, role))
            .map(contributor -> getContributorName(contributor))
            .flatMap(Optional::stream)
            .collect(Collectors.toList());
    }

    private boolean hasRole(Contributor contributor, ContributorRole role) {
        ContributorAttributes attributes = contributor.getContributorAttributes();
        return attributes != null ? attributes.getContributorRole() == role : false;
    }

    private Optional<String> getContributorName(Contributor contributor) {
        return Optional.ofNullable(contributor.getCreditName())
            .map(creditName -> creditName.getContent());
    }

    private List<String> getExternalIds(Work work, String type) {
        ExternalIDs externalIdentifiers = work.getExternalIdentifiers();
        if (externalIdentifiers == null) {
            return emptyList();
        }

        return externalIdentifiers.getExternalIdentifier().stream()
            .filter(externalId -> type.equals(externalId.getType()))
            .map(externalId -> externalId.getValue())
            .collect(Collectors.toList());
    }

    @Override
    public boolean supports(String source) {
        return StringUtils.equals(sourceIdentifier, source);
    }

    @Override
    public int getNumberOfResults(String orcid) {
        return findWorkSummaries(getAccessToken(orcid), orcid, 0, -1).size();
    }

    public void setSourceIdentifier(String sourceIdentifier) {
        this.sourceIdentifier = sourceIdentifier;
    }

    @Override
    public String getSourceIdentifier() {
        return sourceIdentifier;
    }

    public void setFieldMapping(OrcidWorkFieldMapping fieldMapping) {
        this.fieldMapping = fieldMapping;
    }

    public void setReadPublicAccessToken(String readPublicAccessToken) {
        this.readPublicAccessToken = readPublicAccessToken;
    }

    public OrcidClient getOrcidClient() {
        return orcidClient;
    }

    public void setOrcidClient(OrcidClient orcidClient) {
        this.orcidClient = orcidClient;
    }

}
