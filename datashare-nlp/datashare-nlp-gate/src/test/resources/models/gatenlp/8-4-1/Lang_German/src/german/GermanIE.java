package german;

import gate.creole.PackagedController;
import gate.creole.metadata.AutoInstance;
import gate.creole.metadata.AutoInstanceParam;
import gate.creole.metadata.CreoleParameter;
import gate.creole.metadata.CreoleResource;

import java.net.URL;
import java.util.List;

@CreoleResource(name = "German IE System",
    comment = "Ready-made German IE application",
    autoinstances = @AutoInstance(parameters = {
	@AutoInstanceParam(name="pipelineURL", value="resources/german.gapp"),
	@AutoInstanceParam(name="menu", value="German")}))
public class GermanIE extends PackagedController {
  private static final long serialVersionUID = -1983465516568025601L;
}
