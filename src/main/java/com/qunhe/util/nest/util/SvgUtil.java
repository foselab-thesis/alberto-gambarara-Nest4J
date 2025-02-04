package com.qunhe.util.nest.util;

import java.util.ArrayList;
import java.util.List;

import com.qunhe.util.nest.data.NestPath;
import com.qunhe.util.nest.data.Placement;
import com.qunhe.util.nest.data.Segment;

public class SvgUtil {

	/**
	 * The polygons are colored in light red if they are being rotated, in light blue elsewhere
	 * @param list		List<NestPath> to be converted in SVG
	 * @param applied	List<List<Placement>> corresponding to the placements of the NestPaths on the bins
	 * @param binwidth	width of the bins
	 * @param binHeight	height of the bins
	 * @return			List<String> corresponding to the SVG elements
	 * @throws Exception
	 */
	public static List<String> svgGenerator(List<NestPath> list, List<List<Placement>> applied, double binwidth, double binHeight) throws Exception {
		List<String> strings = new ArrayList<>();
		int x = 0;
		int y = 0;
		for (List<Placement> binlist : applied) {
			String s = " <g transform=\"translate(" + x + "  " + y + ")\">" + "\n";
			s += "    <rect x=\"0\" y=\"0\" width=\"" + binwidth + "\" height=\"" + binHeight + "\"  fill=\"none\" stroke=\"#010101\" stroke-width=\"1\" />\n";
			for (Placement placement : binlist) {
				int bid = placement.bid;
				NestPath nestPath = getNestPathByBid(bid, list);
				double ox = placement.translate.x;
				double oy = placement.translate.y;
				double rotate = placement.rotate;
				s += "<g transform=\"translate(" + ox + x + " " + oy + y + ") rotate(" + rotate + ")\"> \n";
				s += "<path d=\"";
				for (int i = 0; i < nestPath.getSegments().size(); i++) {
					if (i == 0) {
						s += "M";
					} else {
						s += "L";
					}
					Segment segment = nestPath.get(i);
					s += segment.x + " " + segment.y + " ";
				}
				String color =(rotate==0.0)?"7bafd1":"fc8d8d";
				s += "Z\" fill=\"#" +color  + "\" stroke=\"#010101\" stroke-width=\"0.5\" />" + " \n";
				s += "</g> \n";
			}
			s += "</g> \n";
			y += binHeight + 50;
			strings.add(s);
		}
		return strings;
	}

	private static NestPath getNestPathByBid(int bid, List<NestPath> list) {
		for (NestPath nestPath : list) {
			if (nestPath.getBid() == bid) {
				return nestPath;
			}
		}
		return null;
	}

}
