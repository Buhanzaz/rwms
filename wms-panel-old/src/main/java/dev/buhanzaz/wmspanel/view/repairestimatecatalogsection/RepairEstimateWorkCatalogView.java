package dev.buhanzaz.wmspanel.view.repairestimatecatalogsection;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNode;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNodeType;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "repair-estimate-catalog-works", layout = MainView.class)
@ViewController(id = "RepairEstimateCatalogWork.view")
@ViewDescriptor(path = "repair-estimate-catalog-work-view.xml")
public class RepairEstimateWorkCatalogView extends AbstractRepairEstimateCatalogSectionView {

    @Override
    protected boolean includeCategoryBySection(RepairEstimateCatalogNode category) {
        return !Boolean.TRUE.equals(category.getFurnitureCategory());
    }

    @Override
    protected RepairEstimateCatalogNodeType sectionType() {
        return RepairEstimateCatalogNodeType.WORK;
    }

    @Override
    protected String sectionTitleKey() {
        return "dev.buhanzaz.wmspanel.view.repairestimatecatalogsection/RepairEstimateWorkCatalogView.title";
    }
}
