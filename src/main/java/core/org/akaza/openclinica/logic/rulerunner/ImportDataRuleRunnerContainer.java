package core.org.akaza.openclinica.logic.rulerunner;

import core.org.akaza.openclinica.bean.admin.CRFBean;
import core.org.akaza.openclinica.bean.managestudy.StudyEventBean;
import core.org.akaza.openclinica.bean.managestudy.StudyEventDefinitionBean;
import core.org.akaza.openclinica.bean.managestudy.StudySubjectBean;
import core.org.akaza.openclinica.bean.submit.CRFVersionBean;
import core.org.akaza.openclinica.bean.submit.ItemBean;
import core.org.akaza.openclinica.bean.submit.ItemGroupMetadataBean;
import core.org.akaza.openclinica.bean.submit.crfdata.FormDataBean;
import core.org.akaza.openclinica.bean.submit.crfdata.ImportItemDataBean;
import core.org.akaza.openclinica.bean.submit.crfdata.ImportItemGroupDataBean;
import core.org.akaza.openclinica.bean.submit.crfdata.StudyEventDataBean;
import core.org.akaza.openclinica.bean.submit.crfdata.SubjectDataBean;
import core.org.akaza.openclinica.dao.admin.CRFDAO;
import core.org.akaza.openclinica.dao.managestudy.StudyEventDAO;
import core.org.akaza.openclinica.dao.managestudy.StudyEventDefinitionDAO;
import core.org.akaza.openclinica.dao.managestudy.StudySubjectDAO;
import core.org.akaza.openclinica.dao.submit.CRFVersionDAO;
import core.org.akaza.openclinica.dao.submit.ItemDAO;
import core.org.akaza.openclinica.dao.submit.ItemGroupMetadataDAO;
import core.org.akaza.openclinica.domain.datamap.Study;
import core.org.akaza.openclinica.domain.rule.RuleSetBean;
import core.org.akaza.openclinica.domain.rule.RuleSetRuleBean;
import core.org.akaza.openclinica.domain.rule.action.RuleActionBean;
import core.org.akaza.openclinica.domain.rule.action.RuleActionRunBean.Phase;
import core.org.akaza.openclinica.service.rule.RuleSetServiceInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

public class ImportDataRuleRunnerContainer {
    private final Logger logger = LoggerFactory.getLogger(getClass().getName());
    /**
     * Key is target itemOID
     */
    private HashMap<String, ArrayList<RuleActionContainer>> RuleActionContainerMap;
    private List<RuleSetBean> importDataTrueRuleSets;
    /**
     * Key is full expression; Value is item data value to be imported.
     */
    private Map<String, String> variableAndValue;
    private String studyOid;
    private String studySubjectOid;
    private Boolean shouldRunRules;

    /**
     * Populate importDataTrueRuleSets and variableAndValue.
     * Precondition: import data file passed validation which means all OIDs are not empty.
     * @param ds
     * @param studyBean
     * @param subjectDataBean
     * @param ruleSetService
     */
    @Transactional
    public void initRuleSetsAndTargets(DataSource ds, Study studyBean, SubjectDataBean subjectDataBean, RuleSetServiceInterface ruleSetService) {
        this.shouldRunRules = this.shouldRunRules == null ? Boolean.FALSE : this.shouldRunRules;
        this.importDataTrueRuleSets = this.importDataTrueRuleSets == null ? new ArrayList<RuleSetBean>() : this.importDataTrueRuleSets;
        this.variableAndValue = this.variableAndValue == null ? new HashMap<String, String>() : this.variableAndValue;
        studyOid = studyBean.getOc_oid();
        studySubjectOid = subjectDataBean.getSubjectOID();
        StudySubjectBean studySubject =
                new StudySubjectDAO<String, ArrayList>(ds).findByOid(studySubjectOid);

        HashMap<String, StudyEventDefinitionBean> seds = new HashMap<String, StudyEventDefinitionBean>();
        HashMap<String, CRFVersionBean> cvs = new HashMap<String, CRFVersionBean>();
        ArrayList<StudyEventDataBean> studyEventDataBeans = subjectDataBean.getStudyEventData();
        for (StudyEventDataBean studyEventDataBean : studyEventDataBeans) {
            String sedOid = studyEventDataBean.getStudyEventOID();
            StudyEventDefinitionBean sed;
            if(seds.containsKey(sedOid))
                sed = seds.get(sedOid);
            else {
                sed = new StudyEventDefinitionDAO<String, ArrayList>(ds).findByOid(sedOid);
                seds.put(sedOid, sed);
            }
            ArrayList<FormDataBean> formDataBeans = studyEventDataBean.getFormData();
            for (FormDataBean formDataBean : formDataBeans) {
                String cvOid = formDataBean.getFormOID();
                CRFVersionBean crfVersion;
                if(cvs.containsKey(cvOid))
                    crfVersion = cvs.get(cvOid);
                else {
                    // crfVersion = new CRFVersionDAO<String, ArrayList>(ds).findByOid(cvOid);
                    crfVersion = getFormVersionBeans(formDataBean,ds);
                    cvs.put(cvOid, crfVersion);
                }
                String sedOrd = studyEventDataBean.getStudyEventRepeatKey();
                Integer sedOrdinal = sedOrd != null && !sedOrd.isEmpty() ? Integer.valueOf(sedOrd) : 1;
                StudyEventBean studyEvent = (StudyEventBean)new StudyEventDAO(ds).findByStudySubjectIdAndDefinitionIdAndOrdinal(
                        studySubject.getId(), sed.getId(), sedOrdinal);
                List<RuleSetBean> ruleSets = ruleSetService.getRuleSetsByCrfStudyAndStudyEventDefinition(studyBean, sed, crfVersion);
                //Set<String> targetItemOids = new HashSet<String>();
                if(ruleSets != null && !ruleSets.isEmpty()) {
                    ruleSets = filterByImportDataEntryTrue(ruleSets);
                    if(ruleSets != null && !ruleSets.isEmpty()) {
                        ruleSets = ruleSetService.filterByStatusEqualsAvailable(ruleSets);
                        ruleSets = ruleSetService.filterRuleSetsByStudyEventOrdinal(ruleSets, studyEvent, crfVersion, sed);
                        //ruleSets = ruleSetService.filterRuleSetsByHiddenItems(ruleSets, eventCrfBean, crfVersion, new ArrayList<ItemBean>());
                        shouldRunRules = ruleSetService.shouldRunRulesForRuleSets(ruleSets, Phase.IMPORT);
                        if(shouldRunRules != null && shouldRunRules == Boolean.TRUE) {
                            //targetItemOids = collectTargetItemOids(ruleSets);

                            HashMap<String, Integer> grouped = new HashMap<String, Integer>();
                            ArrayList<ImportItemGroupDataBean> itemGroupDataBeans = formDataBean.getItemGroupData();
                            for (ImportItemGroupDataBean itemGroupDataBean : itemGroupDataBeans) {
                                ArrayList<ImportItemDataBean> itemDataBeans = itemGroupDataBean.getItemData();
                                for (ImportItemDataBean importItemDataBean : itemDataBeans) {
                                    //if(targetItemOids.contains(importItemDataBean.getItemOID())) {
                                        ItemBean item = new ItemDAO<String, ArrayList>(ds).findByOid(importItemDataBean.getItemOID()).get(0);
                                        String igOid = itemGroupDataBean.getItemGroupOID();
                                        String igOrd = itemGroupDataBean.getItemGroupRepeatKey();
                                        Integer igOrdinal = igOrd != null && !igOrd.isEmpty() ? Integer.valueOf(igOrd) : 1;
                                        //
                                        //logic from DataEntryServlet method: populateRuleSpecificHashMaps()
                                        if(isRepeatIGForSure(ds, crfVersion.getId(), igOid, igOrdinal, item.getId())) {
                                            String key1 = igOid + "[" + igOrdinal + "]." + importItemDataBean.getItemOID();
                                            String key = igOid + "." + importItemDataBean.getItemOID();
                                            variableAndValue.put(key1, importItemDataBean.getValue());
                                            if (grouped.containsKey(key)) {
                                                grouped.put(key, grouped.get(key) + 1);
                                            } else {
                                                grouped.put(key, 1);
                                            }
                                        } else {
                                            variableAndValue.put(importItemDataBean.getItemOID(), importItemDataBean.getValue());
                                            grouped.put(importItemDataBean.getItemOID(), 1);
                                        }
                                        //
                                    //}
                                }
                            }
                            ruleSets = ruleSetService.solidifyGroupOrdinalsUsingFormProperties(ruleSets, grouped);
                            importDataTrueRuleSets.addAll(ruleSets);
                        }
                    }
                }
            }
        }
    }

    @Transactional
    private List<RuleSetBean> filterByImportDataEntryTrue(List<RuleSetBean> ruleSetBeans) {
        List<RuleSetBean> ruleSets = ruleSetBeans;
        if(ruleSets != null) {
            for (RuleSetBean ruleSet : ruleSets) {
                if(ruleSet.getRuleSetRules() != null) {
                    List<RuleSetRuleBean> ruleSetRules = ruleSet.getRuleSetRules();
                    for (RuleSetRuleBean ruleSetRule : ruleSetRules) {
                        List<RuleActionBean> ruleActions = ruleSetRule.getActions();
                        if(ruleActions != null && ruleActions.size() > 0) {
                            for(Iterator<RuleActionBean> ra = ruleActions.iterator(); ra.hasNext();) {
                                RuleActionBean ruleAction = ra.next();
                                if(!ruleAction.getRuleActionRun().canRun(Phase.IMPORT)) {
                                    ra.remove();
                                }
                            }
                        }
                    }
                }
            }
        }
        return ruleSets;
    }

    private Set<String> collectTargetItemOids(List<RuleSetBean> ruleSets) {
        Set<String> itemOids = new HashSet<String>();
        if(ruleSets != null) {
            for(RuleSetBean ruleSetBean : ruleSets) {
                itemOids.add(ruleSetBean.getItem().getOid());
            }
        }
        return itemOids;
    }

    private boolean isRepeatIGForSure(DataSource ds, Integer crfVersionId,  String itemGroupOid, Integer igOrdinal, Integer itemId) {
        boolean isRepeatForSure = igOrdinal != null && igOrdinal > 1 ? true : false;
        if(!isRepeatForSure) {
            if(itemGroupOid.endsWith("_UNGROUPED") || itemGroupOid.contains("_UNGROUPED_")) isRepeatForSure = false;
            else {
                ItemGroupMetadataBean itemGroupMetadataBean =
                    (ItemGroupMetadataBean)new ItemGroupMetadataDAO<String, ArrayList>(ds).findByItemAndCrfVersion(itemId, crfVersionId);
                isRepeatForSure = itemGroupMetadataBean.isRepeatingGroup();
            }
        }
        return isRepeatForSure;
    }


    public String getStudyOid() {
        return studyOid;
    }

    public void setStudyOid(String studyOid) {
        this.studyOid = studyOid;
    }

    public String getStudySubjectOid() {
        return studySubjectOid;
    }

    public void setStudySubjectOid(String studySubjectOid) {
        this.studySubjectOid = studySubjectOid;
    }

    public List<RuleSetBean> getImportDataTrueRuleSets() {
        return importDataTrueRuleSets;
    }

    public void setImportDataTrueRuleSets(List<RuleSetBean> importDataTrueRuleSets) {
        this.importDataTrueRuleSets = importDataTrueRuleSets;
    }

    public Map<String, String> getVariableAndValue() {
        return variableAndValue;
    }

    public void setVariableAndValue(Map<String, String> variableAndValue) {
        this.variableAndValue = variableAndValue;
    }

    public HashMap<String, ArrayList<RuleActionContainer>> getRuleActionContainerMap() {
        return RuleActionContainerMap;
    }

    public void setRuleActionContainerMap(HashMap<String, ArrayList<RuleActionContainer>> ruleActionContainerMap) {
        RuleActionContainerMap = ruleActionContainerMap;
    }

    public Boolean getShouldRunRules() {
        return shouldRunRules;
    }

    public void setShouldRunRules(Boolean shouldRunRules) {
        this.shouldRunRules = shouldRunRules;
    }

    private CRFVersionBean getFormVersionBeans(FormDataBean formDataBean, DataSource ds){
        CRFVersionDAO formVersionDAO = new CRFVersionDAO(ds);
        CRFDAO crfDAO = new CRFDAO(ds);
        CRFVersionBean crfVersionBean = null;

        String formOid = formDataBean.getFormOID();
        CRFBean crfBean = crfDAO.findByOid(formOid);
        if(crfBean != null){
            crfVersionBean =  (CRFVersionBean) formVersionDAO.findByFullName(formDataBean.getFormLayoutName(),crfBean.getName());
        }

        return crfVersionBean;
    }

}
