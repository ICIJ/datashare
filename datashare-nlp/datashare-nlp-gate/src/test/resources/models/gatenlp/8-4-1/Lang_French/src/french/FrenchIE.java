package french;

import gate.creole.PackagedController;
import gate.creole.metadata.AutoInstance;
import gate.creole.metadata.AutoInstanceParam;
import gate.creole.metadata.CreoleResource;

@CreoleResource(name = "French IE System",
    comment = "Ready-made French IE application",
    autoinstances = @AutoInstance(parameters = {
	@AutoInstanceParam(name="pipelineURL", value="french.gapp"),
	@AutoInstanceParam(name="menu", value="French")}))
public class FrenchIE extends PackagedController {

  private static final long serialVersionUID = 5965895217733412732L;

}
